/**
 * Copyright 2012 University of South Florida
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *        http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package edu.usf.cutr.realtime.hart.services;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeMutableProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;
import com.google.transit.realtime.GtfsRealtimeOneBusAway.OneBusAwayTripUpdate;

import edu.usf.cutr.realtime.hart.models.TransitDataV2;
import edu.usf.cutr.realtime.hart.rt.fake.StopTimesCsvDecrypt;
import edu.usf.cutr.realtime.hart.sql.ResultSetDecryptV2;
import edu.usf.cutr.realtime.hart.sql.RetrieveTransitDataV2;
import edu.usf.cutr.realtime.hart.sql.connection.Properties;

/**
 * 
 * @author Khoa Tran
 *
 */

@Singleton
public class HartToGtfsRealtimeServiceV2{
	private static final Logger _log = LoggerFactory.getLogger(HartToGtfsRealtimeServiceV2.class);

	private volatile FeedMessage _tripUpdatesMessage = GtfsRealtimeLibrary.createFeedMessageBuilder().build();

	private volatile FeedMessage _vehiclePositionsMessage = GtfsRealtimeLibrary.createFeedMessageBuilder().build();

//	private final FeedMessage _alertsMessage = GtfsRealtimeLibrary.createFeedMessageBuilder().build();
	
	private GtfsRealtimeMutableProvider _gtfsRealtimeProvider;
	
	private ScheduledExecutorService _refreshExecutor;
	
	private ScheduledExecutorService _delayExecutor;

	private Connection _conn = null;
	
	private Integer _queryTimeout = null;

	private RetrieveTransitDataV2 _rtd = null;
	
	private final String TRIP_UPDATE_PREFIX = "trip_update_";
	
	private final String VEHICLE_POSITION_PREFIX = "vehicle_position_";

	/**
	 * How often data will be updated, in seconds
	 */
	private int _refreshInterval = 30;

	public HartToGtfsRealtimeServiceV2(){
		Properties connProps = getConnectionProperties();
		_conn = getConnection(connProps);
		_rtd = new RetrieveTransitDataV2();
	}
	
	public void setRefreshInterval(int refreshInterval) {
    _refreshInterval = refreshInterval;
  }
	
	@Inject
	public void setGtfsRealtimeProvider(GtfsRealtimeMutableProvider gtfsRealtimeProvider) {
    _gtfsRealtimeProvider = gtfsRealtimeProvider;
  }

	@PostConstruct
	public void start() {
		_log.info("starting GTFS-realtime service");
		_refreshExecutor = Executors.newSingleThreadScheduledExecutor();
		_refreshExecutor.scheduleAtFixedRate(new RefreshTransitData(), 0,
				_refreshInterval, TimeUnit.SECONDS);
		_delayExecutor = Executors.newSingleThreadScheduledExecutor();
    _delayExecutor.scheduleAtFixedRate(new DelayThread(), _refreshInterval,
        _refreshInterval/4, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void stop() {
		_log.info("stopping GTFS-realtime service");
		_refreshExecutor.shutdownNow();
		_delayExecutor.shutdownNow();
	}

	private Properties getConnectionProperties(){		
		Properties connProps = new Properties();
		try {
			connProps.load(new FileInputStream("./config.properties"));
			_queryTimeout = connProps.getQueryTimeout();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			_log.error("Config file is not found: " + e.getMessage());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			_log.error("Failed to read connection properties: " + e.getMessage());
		}
		return connProps;
	}

	private Connection getConnection(Properties connProps){
		Connection conn = null;
		String connString = 
				"jdbc:sqlserver://" + connProps.getHost() + 
				":" + connProps.getPortNumber() + 
				";database=" + connProps.getDatabaseName() + 
				";user=" + connProps.getUser() + 
				";password=" + connProps.getPassword() +
				";loginTimeout=" + 30;

		_log.info("Connection String: "+connString);

		try {
		  DriverManager.setLoginTimeout(30); // 30 seconds
			conn = DriverManager.getConnection(connString);
			_log.debug("connection established");
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			_log.error("Failed to connect to " + connProps.getDatabaseName() + " database: " + e.getMessage());
		}
		
		return conn;
	}

	 private void cleanup() {
	   // for now we just measure a successful connection/disconnect from db, we don't look at data
	   _gtfsRealtimeProvider.setLastUpdateTimestamp(System.currentTimeMillis());
	   _log.debug("closing connection");
	    try {
	      if (_conn != null) {
	        _conn.close();
	      }
	    } catch (Exception bury) {
	      // we don't care
	    }
	    _conn = null;
	  }

	private ArrayList<TransitDataV2> getOrbcadTransitData() throws Exception{
		_log.debug("pre query");
		ResultSet rs = _rtd.executeQuery(_conn, _queryTimeout);
		if(rs == null){
		  _conn = null;
		  throw new Exception("ResultSet for SELECT query is null");
		}
		_log.debug("post query");
		ResultSetDecryptV2 rsd = new ResultSetDecryptV2(rs);
		_log.debug("pre decrypt");
		ArrayList<TransitDataV2> transitData = rsd.decrypt();
		_log.debug("pre decrypt");
		//_log.debug(transitData.toString());
		cleanup();
		return transitData;
	}
	

  private ArrayList<TransitDataV2> getOrbcadTransitDataFake(){
	  StopTimesCsvDecrypt stcd = new StopTimesCsvDecrypt();
    ArrayList<TransitDataV2> transitData = stcd.decrypt();
    _log.debug(transitData.toString());
    return transitData;
  }

	private void buildTripUpdates(ArrayList<TransitDataV2> transitData){
		FeedMessage.Builder tripUpdates = GtfsRealtimeLibrary.createFeedMessageBuilder();

		
		ArrayList<StopTimeUpdate> stopTimeUpdateSet = new ArrayList<StopTimeUpdate>();

		for(int i=0; i<transitData.size(); i++){
			TransitDataV2 td = transitData.get(i);
			
			String vehicleId = td.getVehicleId();
			int delay = td.getDelay();  // in seconds
			double lat = td.getVehicleLat();
			double lon = td.getVehicleLon();
			int speed = td.getVehicleSpeed();
			int bearing = td.getVehicleBearing();
			int seq = td.getSequenceNumber();
			Timestamp time = td.getVehicleTime();
			String stopId = td.getStopId();
			String routeId = td.getRouteId();
			String tripId = td.getTripId();
			
			/**
       * StopTime Event
       */
      StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();
      arrival.setDelay(delay);
      arrival.setUncertainty(30);
      
			/**
       * StopTime Update
       */
      StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate.newBuilder();
      if(stopId==null){
        continue;
      }
      stopTimeUpdate.setStopSequence(seq);
      stopTimeUpdate.setStopId(stopId);
      stopTimeUpdate.setArrival(arrival);
      // Google requested adding departure delays for Google Transit (Issue #7).
      // Since we don't have explicit departure delay info from OrbCAD,
      // at the suggestion of Google we will just use arrival delay as a substitute
      stopTimeUpdate.setDeparture(arrival);  
      
      stopTimeUpdateSet.add(stopTimeUpdate.build());
      
      if(i!=transitData.size()-1 && tripId.equalsIgnoreCase(transitData.get(i+1).getTripId())){
        continue;
      }

			/**
			 * Trip Descriptor
			 */
			TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
			tripDescriptor.setTripId(tripId);

			/**
			 * Vehicle Descriptor
			 */
			VehicleDescriptor.Builder vehicleDescriptor = VehicleDescriptor.newBuilder();
			if(vehicleId!=null && !vehicleId.isEmpty()) {
			  vehicleDescriptor.setId(vehicleId);
			}
			
			TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();
			tripUpdate.addAllStopTimeUpdate(stopTimeUpdateSet);
			stopTimeUpdateSet.clear();
			tripUpdate.setTrip(tripDescriptor);
			if(vehicleId!=null && !vehicleId.isEmpty()) {
			  tripUpdate.setVehicle(vehicleDescriptor);
			}
			tripUpdate.setTimestamp(time.getTime()/1000);
			
//			OneBusAwayTripUpdate.Builder obaTripUpdate = OneBusAwayTripUpdate.newBuilder();
//      obaTripUpdate.setDelay(delay);
//      tripUpdate.setExtension(GtfsRealtimeOneBusAway.obaTripUpdate, obaTripUpdate.build());

			FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
			tripUpdateEntity.setId(TRIP_UPDATE_PREFIX+tripId);
			tripUpdateEntity.setTripUpdate(tripUpdate);
			tripUpdates.addEntity(tripUpdateEntity);
		}

		_tripUpdatesMessage = tripUpdates.build();
		_gtfsRealtimeProvider.setTripUpdates(_tripUpdatesMessage);
	}

	private void buildVehiclePositions(ArrayList<TransitDataV2> transitData){
		FeedMessage.Builder vehiclePositions = GtfsRealtimeLibrary.createFeedMessageBuilder();

		HashSet<String> vehicleIdSet = new HashSet<String>();
		
		for(int i=0; i<transitData.size(); i++){
		  TransitDataV2 td = transitData.get(i);
      
      String vehicleId = td.getVehicleId();
      int delay = td.getDelay();  // in seconds
      double lat = td.getVehicleLat();
      double lon = td.getVehicleLon();
      int speed = td.getVehicleSpeed();
      int bearing = td.getVehicleBearing();
      int seq = td.getSequenceNumber();
      Timestamp time = td.getVehicleTime();
      String stopId = td.getStopId();
      String routeId = td.getRouteId();
      String tripId = td.getTripId();
      
      if(!vehicleIdSet.contains(vehicleId)){
        vehicleIdSet.add(vehicleId);
      } else {
        continue;
      }

			/**
			 * Trip Descriptor
			 */
			TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
			tripDescriptor.setTripId(tripId);

			/**
			 * Vehicle Descriptor
			 */
			VehicleDescriptor.Builder vehicleDescriptor = VehicleDescriptor.newBuilder();
			vehicleDescriptor.setId(vehicleId);

			/**
			 * To construct our VehiclePosition, we create a position for the vehicle.
			 * We add the position to a VehiclePosition builder, along with the trip
			 * and vehicle descriptors.
			 */
			Position.Builder position = Position.newBuilder();
			position.setLatitude((float) lat);
			position.setLongitude((float) lon);
			position.setSpeed((float) speed);
			position.setBearing((float) bearing);

			VehiclePosition.Builder vehiclePosition = VehiclePosition.newBuilder();
			vehiclePosition.setPosition(position);
			vehiclePosition.setTrip(tripDescriptor);
			vehiclePosition.setVehicle(vehicleDescriptor);
			vehiclePosition.setTimestamp(time.getTime()/1000);

			FeedEntity.Builder vehiclePositionEntity = FeedEntity.newBuilder();
			vehiclePositionEntity.setId(VEHICLE_POSITION_PREFIX+vehicleId);
			vehiclePositionEntity.setVehicle(vehiclePosition);

			vehiclePositions.addEntity(vehiclePositionEntity);
		}

		_vehiclePositionsMessage = vehiclePositions.build();
		_gtfsRealtimeProvider.setVehiclePositions(_vehiclePositionsMessage);
	}

	public void writeGtfsRealtimeOutput() throws Exception {
		_log.debug("Writing Hart GTFS realtime...");
		if(_conn==null) {
      Properties connProps = getConnectionProperties();
      _conn = getConnection(connProps);
    }
		if(_conn==null) {
		  throw new Exception("Database Connection is null");
		} else {
		  ArrayList<TransitDataV2> transitData = getOrbcadTransitData();
//		  ArrayList<TransitDataV2> transitData = getOrbcadTransitDataFake();
	    buildTripUpdates(transitData);
	    buildVehiclePositions(transitData);
	    _log.info("tripUpdates = "+_tripUpdatesMessage.getEntityCount() + ", " + "vehiclePositions = "+_vehiclePositionsMessage.getEntityCount());
	    _log.debug("vehiclePositions = "+_vehiclePositionsMessage.getEntityCount());
		}
	}

	private class RefreshTransitData implements Runnable {
		public void run() {
			try {
				_log.debug("refreshing vehicles");
				writeGtfsRealtimeOutput();
			} catch (Exception ex) {
				_log.error("Failed to refresh TransitData: " + ex.getMessage());
			}
		}
	}
	
	private class DelayThread implements Runnable {
	  public void run() {
	    long hangTime = (System.currentTimeMillis() - _gtfsRealtimeProvider.getLastUpdateTimestamp()) / 1000;
	    if (hangTime> ((_refreshInterval * 2) - (_refreshInterval / 2))) {
  	    // if we've reached here, the connection to the database has hung
	      // we assume a service-based configuration and simply exit
	      // TODO adjust network/driver timeouts instead!
	      _log.error("Connection hung with delay of " + hangTime + ".  Exiting!");
	      System.exit(1);
	    } else {
	      _log.info("hangTime:" + hangTime);
	    }
	  }
	}
}
