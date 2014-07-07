/*
*
*    Copyright (C): 2010-2011 Forschungszentrum Telekommunikation Wien (FTW)
*    Contact: Danilo Valerio <valerio@ftw.at>
*
*    This file is part of Mobilog for Android.
*
*    Mobilog for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 2 of the License, or
*    (at your option) any later version.
*
*    Mobilog for Android is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with Mobilog for Android.  If not, see <http://www.gnu.org/licenses/>.
*/

package at.ftw.mobilitylogger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.widget.Toast;

public class LoggingService extends Service {
	public static final int NOTIFICATION_ID = 1;
	private NotificationManager notificationManager;

	// User chosen preferences
//	private boolean isFirstTime;
//	private boolean isLogging;
	private int minimumDistance;
	private int minimumSeconds;
	private long updateInterval;	
	private boolean kml_export;
	private boolean rssi_logging;
	private boolean wifi_logging;
	private boolean continuous_wifi_scanning;
	private boolean neighbors_logging;
	private boolean cellid_logging;
	private int n_datapoints=0;
	
	PowerManager powerManager;
	PowerManager.WakeLock wakeLock;
	LocationManager srvcLocationManager;
	TelephonyManager srvcTelephonyManager;
	WifiManager srvcWifiManager;
	WifiReceiver srvcWifiReceiver;

	// Timer object to execute a routine at fixed rate
	private Timer timer = new Timer();
	
	SharedPreferences prefs;
	SharedPreferences.Editor prefEditor;


	OutputStreamWriter osw_txt;
	OutputStreamWriter osw_kml;
	
	private long gpsTimestamp = -1;
	private double gpsLatitude = -1;
	private double gpsLongitude = -1;
	private double gpsAltitude = -1;
	private float gpsSpeed = -1;
	private float gpsBearing = -1;
	private float gpsAccuracy = -1;
	private int gpsSatellites = -1;
	private String mcc, mnc;
	private int lac, cellid, previouslac, previouscellid;
	private String nettype, dataactivity, datastate, callstate;
	private Map<Integer,Integer> neighborsmapUMTS = new HashMap<Integer,Integer>();
	private Map<String,Integer> neighborsmapGSM = new HashMap<String,Integer>();
	private int RSSI;
	List<ScanResult> wifiList;

	
	@Override
	public void onCreate(){
		//executed when the service is first created
		//here we should initialize variables and get reference to UI objects
		showNotification();
		prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		prefEditor = prefs.edit();
		getUserPreferences();

		powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "RSSI_SCREEN_ON");

		srvcLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		srvcTelephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
		srvcWifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		   
		File directory = new File (Environment.getExternalStorageDirectory().getAbsolutePath() + "/mobilog");
		if (!directory.exists())
			directory.mkdirs();
		String filename = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(System.currentTimeMillis());
		File outputfile_txt = new File(directory, filename+".txt");
		try {
			FileOutputStream fOut_txt = new FileOutputStream(outputfile_txt, true);
			osw_txt = new OutputStreamWriter(fOut_txt);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		if (kml_export){
			File outputfile_kml = new File(directory, filename+".kml");
			try {
				FileOutputStream fOut_kml = new FileOutputStream(outputfile_kml, true);
				osw_kml = new OutputStreamWriter(fOut_kml);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			kml_writeheader();
			Toast.makeText(getBaseContext(), "Service created \n" + outputfile_txt.getAbsolutePath() 
					+ "\n" + outputfile_kml.getAbsolutePath(), Toast.LENGTH_LONG).show();	
		} else {
			Toast.makeText(getBaseContext(), "Service created \n" + outputfile_txt.getAbsolutePath(), Toast.LENGTH_LONG).show();
		}

		prefEditor.putBoolean("isLogging", true);
		prefEditor.putString("loggingSince", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
//		prefEditor.putInt("n_datapoints", n_datapoints);
		prefEditor.commit();

		//Initialize a couple of value, otherwise the first line contains NULLs
		initialize();
		startLogging();
	}

	@Override
	public IBinder onBind(Intent intent){
		//executed on bind. Useless in this app, but mandatory.
		return null;
	}
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		//executed whenever the service is (re)started.
		//if ((flags & START_FLAG_RETRY) == 0) {
			//Toast.makeText(getBaseContext(), "Service restarted", Toast.LENGTH_SHORT).show();
			//Restart monitoring
		//}
		//else{
			//Toast.makeText(getBaseContext(), "Service connected", Toast.LENGTH_SHORT).show();
		//}
		return Service.START_STICKY;
	}

	@Override
    public void onDestroy() {
        // Close all logging tasks
		if (timer != null) {
			timer.cancel();
		}
		srvcLocationManager.removeUpdates(srvcLocationListener);
		srvcTelephonyManager.listen(srvcPhoneStateListener, PhoneStateListener.LISTEN_NONE);
		//Toast.makeText(getBaseContext(), "Service destroyed", Toast.LENGTH_SHORT).show();
		if (rssi_logging||neighbors_logging)
			wakeLock.release();
		if (wifi_logging)
			unregisterReceiver(srvcWifiReceiver);

		// Remove status bar icon and set pref values
		notificationManager.cancel(NOTIFICATION_ID);
		prefEditor.putBoolean("isLogging", false);
		prefEditor.putString("loggingSince", null);
//		prefEditor.putInt("n_datapoints", 0);
		prefEditor.commit();
		
		// Flush and close files
		try {
			osw_txt.flush();
			osw_txt.close();
			Toast.makeText(getBaseContext(), "csv file closed", Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (kml_export){
			try {
				kml_writetail();
				osw_kml.flush();
				osw_kml.close();
				Toast.makeText(getBaseContext(), "kml file closed", Toast.LENGTH_SHORT).show();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		n_datapoints=0;
	}

	public void startLogging(){
		
		/** Wifi broadcast Receiver*/
		if (wifi_logging){
			srvcWifiReceiver = new WifiReceiver();
			registerReceiver(srvcWifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
			srvcWifiManager.startScan();
		}

		/**Location Manager, Provider, and Listener*/
		srvcLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minimumSeconds * 1000, minimumDistance, srvcLocationListener);

		/**Telephony Manager and Listener*/
		if (rssi_logging||neighbors_logging)
		{
			/**Register to the listener*/
			wakeLock.acquire();
			srvcTelephonyManager.listen(srvcPhoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION |
					PhoneStateListener.LISTEN_DATA_ACTIVITY | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE |
					PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | PhoneStateListener.LISTEN_CALL_STATE);
		} else {
			srvcTelephonyManager.listen(srvcPhoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION |
					PhoneStateListener.LISTEN_DATA_ACTIVITY | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
					 | PhoneStateListener.LISTEN_CALL_STATE);
		}
		if (updateInterval>0)
			timer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					writeDataPoint();
					if (kml_export) 
						kml_writeplacemark();
				}
			}, 1000, updateInterval*1000);
	}
	
	/** GPS Listener */
	private final LocationListener srvcLocationListener = new LocationListener() {
		public void onLocationChanged(Location location){
			gpsTimestamp = location.getTime();
			gpsLatitude = location.getLatitude();
			gpsLongitude = location.getLongitude();
			gpsAltitude = location.getAltitude();
			gpsSpeed = location.getSpeed();
			gpsBearing = location.getBearing();
			gpsAccuracy = location.getAccuracy();
			gpsSatellites = location.getExtras().getInt("satellites");
			if (updateInterval==0){
				writeDataPoint();
				if (kml_export) 
					kml_writeplacemark();
			}
		}
		public void onProviderDisabled(String Provider){
			gpsTimestamp = -1;
			gpsLatitude = -1;
			gpsLongitude = -1;
			gpsAltitude = -1;
			gpsSpeed = -1;
			gpsBearing = -1;
			gpsAccuracy = -1;
			gpsSatellites = -1;
			}
		public void onProviderEnabled(String provider){}
		public void onStatusChanged(String provider, int status, Bundle extras) {
			if (status == LocationProvider.OUT_OF_SERVICE || status == LocationProvider.TEMPORARILY_UNAVAILABLE){
				gpsTimestamp = -1;
				gpsLatitude = -1;
				gpsLongitude = -1;
				gpsAltitude = -1;
				gpsSpeed = -1;
				gpsBearing = -1;
				gpsAccuracy = -1;
				gpsSatellites = -1;
			}			
		}
	};

	PhoneStateListener srvcPhoneStateListener = new PhoneStateListener() {
		public void onCellLocationChanged(CellLocation location){
			String mccmnc = srvcTelephonyManager.getNetworkOperator();
	
			/** MCC and MNC. the check is required, otherwise crash when no coverage is available*/
			if (mccmnc != null && mccmnc.length() >= 4) {
				mcc = mccmnc.substring(0,3);
				mnc = mccmnc.substring(3);
			} else {
				mcc = "-1";
				mnc = "-1";
			}
	
			/** LAC and Cellid*/
			GsmCellLocation gsmLocation = (GsmCellLocation)location;
			lac=gsmLocation.getLac();
			cellid=gsmLocation.getCid() & 0xffff;
			//if (lac == 65535) lac=-1; 
			//if (cellid == 65535) cellid=-1;
			switch (srvcTelephonyManager.getNetworkType()) {
				case (TelephonyManager.NETWORK_TYPE_1xRTT): nettype = "1xRTT"; break;
				case (TelephonyManager.NETWORK_TYPE_CDMA): nettype = "CDMA"; break;
				case (TelephonyManager.NETWORK_TYPE_EDGE): nettype = "EDGE"; break;
				case (TelephonyManager.NETWORK_TYPE_EVDO_0): nettype = "EVDO_0"; break;
				case (TelephonyManager.NETWORK_TYPE_EVDO_A): nettype = "EVDO_A"; break;
				case (TelephonyManager.NETWORK_TYPE_HSDPA): nettype = "HSDPA"; break;
				case (TelephonyManager.NETWORK_TYPE_HSPA): nettype = "HSPA"; break;
				case (TelephonyManager.NETWORK_TYPE_HSUPA): nettype = "HSUPA"; break;
				case (TelephonyManager.NETWORK_TYPE_UMTS): nettype = "UMTS"; break;
				case (TelephonyManager.NETWORK_TYPE_UNKNOWN): nettype = "UNKNOWN"; break;
				default: break;
			}

			if (cellid_logging && updateInterval==0){
				// if we change cell write datapoint to the CSV file. KML is not required as GPS info is redundant
				writeDataPoint();
			}
			if (neighbors_logging){
				neighborsmapGSM.clear(); //clean the list of neighbors (GSM networks)
				neighborsmapUMTS.clear(); //clean the list of neighbors (UMTS networks)
			}
		}
		
		public void onSignalStrengthsChanged(SignalStrength signalStrength){
			RSSI = (-113+(2*signalStrength.getGsmSignalStrength()));	
			if (neighbors_logging){
				for (String key: neighborsmapGSM.keySet())  
					neighborsmapGSM.put(key,-113); // Reinitialize the RSSI of all the neighbors
				for (int key: neighborsmapUMTS.keySet())
					neighborsmapUMTS.put(key,-115); // Reinitialize the RSSI of all the neighbors
				updateNeighboringCells(); //update the RSSI of the existing neighbors
			}
		}
		
		private void updateNeighboringCells(){
			/** Neighboring cells */
			List<NeighboringCellInfo> neighboringCellInfo;
			neighboringCellInfo = srvcTelephonyManager.getNeighboringCellInfo();

			/** Fill the hash tables depending on the network type*/
			for (NeighboringCellInfo i : neighboringCellInfo) {
				int networktype=i.getNetworkType();
				if ((networktype == TelephonyManager.NETWORK_TYPE_UMTS) || 
						(networktype == TelephonyManager.NETWORK_TYPE_HSDPA) ||
						(networktype == TelephonyManager.NETWORK_TYPE_HSUPA) ||
						(networktype == TelephonyManager.NETWORK_TYPE_HSPA))
					neighborsmapUMTS.put(i.getPsc(), i.getRssi()-115);
				else 
					neighborsmapGSM.put(i.getLac()+"-"+i.getCid(), (-113+2*(i.getRssi())));
			}
		}
			
		public void onDataActivity(int direction){
			switch(direction) {
				case TelephonyManager.DATA_ACTIVITY_IN: dataactivity = "DATA_IN"; break;
				case TelephonyManager.DATA_ACTIVITY_OUT: dataactivity = "DATA_OUT"; break;
				case TelephonyManager.DATA_ACTIVITY_INOUT: dataactivity = "DATA_INOUT"; break;
				case TelephonyManager.DATA_ACTIVITY_NONE: dataactivity = "DATA_NONE"; break;
				case TelephonyManager.DATA_ACTIVITY_DORMANT: dataactivity = "DATA_DORMANT"; break;
			}
		}
		public void onDataConnectionStateChanged(int state){
			switch(state){
				case TelephonyManager.DATA_CONNECTED: datastate = "DATA_CONNECTED"; break;
				case TelephonyManager.DATA_CONNECTING: datastate = "DATA_CONNECTING"; break;
				case TelephonyManager.DATA_DISCONNECTED: datastate = "DATA_DISCONNECTED"; break;
				case TelephonyManager.DATA_SUSPENDED: datastate = "DATA_SUSPENDED"; break;
			}
		}
		public void onCallStateChanged(int state, String incomingNumber){
			switch(state){
			case TelephonyManager.CALL_STATE_IDLE: callstate = "CALL_IDLE"; break;
			case TelephonyManager.CALL_STATE_OFFHOOK: callstate = "CALL_OFFHOOK"; break;
			case TelephonyManager.CALL_STATE_RINGING: callstate = "CALL_RINGING"; break;			
			}
		}
	};

	
	public void getUserPreferences()
	{
		//isLogging = prefs.getBoolean("isLogging", false); //We do not need this here
		//isFirstTime = prefs.getBoolean("firsttime", true); //We do not need this here
		kml_export = prefs.getBoolean("kml_export", false);
		rssi_logging = prefs.getBoolean("rssi_logging",true);
		cellid_logging = prefs.getBoolean("cellid_logging", true);
		wifi_logging = prefs.getBoolean("wifi_logging", false);
		continuous_wifi_scanning = prefs.getBoolean("continuous_wifi_scanning", false);
		neighbors_logging = prefs.getBoolean("neighbors_logging", false);
		String minimumDistanceString = prefs.getString("update_interval_meters", "10");
		if (minimumDistanceString != null && minimumDistanceString.length() > 0){
			minimumDistance = Integer.valueOf(minimumDistanceString);
		} else{
			minimumDistance = 10;
		}
		String minimumSecondsString = prefs.getString("update_interval_seconds", "0");
		if (minimumSecondsString != null && minimumSecondsString.length() > 0){
			minimumSeconds = Integer.valueOf(minimumSecondsString);
		} else{
			minimumSeconds = 0;
		}
		String updateIntervalString = prefs.getString("update_interval", "0");
		if (updateIntervalString != null && updateIntervalString.length() > 0){
			updateInterval = Long.valueOf(updateIntervalString);
		} else{
			updateInterval = 0;
		}
	}

	private void showNotification(){
		int icon = R.drawable.signal_statusbar;
		notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		Notification notification = new Notification(icon,"Mobility Logger is running",System.currentTimeMillis());

		// The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, "Mobility Logger","Mobility Logger is running", contentIntent);

        // Fire the notification
        notificationManager.notify(NOTIFICATION_ID, notification);

	}

	public void initialize(){
		switch(srvcTelephonyManager.getDataActivity()) {
			case TelephonyManager.DATA_ACTIVITY_IN: dataactivity = "DATA_IN"; break;
			case TelephonyManager.DATA_ACTIVITY_OUT: dataactivity = "DATA_OUT"; break;
			case TelephonyManager.DATA_ACTIVITY_INOUT: dataactivity = "DATA_INOUT"; break;
			case TelephonyManager.DATA_ACTIVITY_NONE: dataactivity = "DATA_NONE"; break;
			case TelephonyManager.DATA_ACTIVITY_DORMANT: dataactivity = "DATA_DORMANT"; break;
		}
		switch(srvcTelephonyManager.getDataState()){
			case TelephonyManager.DATA_CONNECTED: datastate = "DATA_CONNECTED"; break;
			case TelephonyManager.DATA_CONNECTING: datastate = "DATA_CONNECTING"; break;
			case TelephonyManager.DATA_DISCONNECTED: datastate = "DATA_DISCONNECTED"; break;
			case TelephonyManager.DATA_SUSPENDED: datastate = "DATA_SUSPENDED"; break;
		}
		switch (srvcTelephonyManager.getNetworkType()) {
			case (TelephonyManager.NETWORK_TYPE_1xRTT): nettype = "1xRTT"; break;
			case (TelephonyManager.NETWORK_TYPE_CDMA): nettype = "CDMA"; break;
			case (TelephonyManager.NETWORK_TYPE_EDGE): nettype = "EDGE"; break;
			case (TelephonyManager.NETWORK_TYPE_EVDO_0): nettype = "EVDO_0"; break;
			case (TelephonyManager.NETWORK_TYPE_EVDO_A): nettype = "EVDO_A"; break;
			case (TelephonyManager.NETWORK_TYPE_HSDPA): nettype = "HSDPA"; break;
			case (TelephonyManager.NETWORK_TYPE_HSPA): nettype = "HSPA"; break;
			case (TelephonyManager.NETWORK_TYPE_HSUPA): nettype = "HSUPA"; break;
			case (TelephonyManager.NETWORK_TYPE_UMTS): nettype = "UMTS"; break;
			case (TelephonyManager.NETWORK_TYPE_UNKNOWN): nettype = "UNKNOWN"; break;
		}
		switch(srvcTelephonyManager.getCallState()){
			case TelephonyManager.CALL_STATE_IDLE: callstate = "CALL_IDLE"; break;
			case TelephonyManager.CALL_STATE_OFFHOOK: callstate = "CALL_OFFHOOK"; break;
			case TelephonyManager.CALL_STATE_RINGING: callstate = "CALL_RINGING"; break;			
		}
	}
	public void writeDataPoint(){
		StringBuilder record = new StringBuilder();
		record.append(System.currentTimeMillis()).append(";");
		record.append(gpsTimestamp).append(";");
		record.append(gpsLatitude).append(";");
		record.append(gpsLongitude).append(";");
		record.append(gpsAltitude).append(";");
		record.append(gpsSpeed).append(";");
		record.append(gpsBearing).append(";");
		record.append(gpsAccuracy).append(";");
		record.append(gpsSatellites).append(";");
		record.append(mcc).append(";");
		record.append(mnc).append(";");
		record.append(lac).append(";");
		record.append(cellid).append(";");
		if (rssi_logging)
			record.append(RSSI);
		record.append(";");
		record.append(nettype).append(";");
		record.append(datastate).append(";");
		record.append(dataactivity).append(";");
		record.append(callstate).append(";");
		if (neighbors_logging){
			int n_neighboringcells = neighborsmapUMTS.size() + neighborsmapGSM.size();
			record.append(n_neighboringcells);
			if (!neighborsmapUMTS.isEmpty()){
				for (Object key: neighborsmapUMTS.keySet()) {
					record.append("|PSC:").append(key).append(",RSCP:").append(neighborsmapUMTS.get(key));
				}
			}
			if (!neighborsmapGSM.isEmpty()){
				for (Object key: neighborsmapGSM.keySet()) {
					record.append("|LAC-CID:").append(key).append(",RSSI:").append(neighborsmapGSM.get(key));
				}
			}
		} 
		record.append(";");
		if (wifi_logging)
			if (wifiList!=null){
				record.append(String.valueOf(wifiList.size()));
				for (int i=0; i<wifiList.size();i++){
					record.append("|SSID:").append(wifiList.get(i).SSID)
					.append(",BSSID:").append(wifiList.get(i).BSSID)
					.append(",FREQ:").append(wifiList.get(i).frequency)
					.append(",RSSI:").append(wifiList.get(i).level)
					.append(",ENCR:").append(wifiList.get(i).capabilities);
				}
			} else record.append("0");
		record.append(";\n");

		try {
			osw_txt.append(record);
		} catch (IOException e) {
			e.printStackTrace();
		}		
		n_datapoints++;
//		prefEditor.putInt("n_datapoints", n_datapoints);
//		prefEditor.commit();
		//Toast.makeText(getBaseContext(), record, Toast.LENGTH_LONG).show();
	}

	public void kml_writeheader(){
		StringBuilder header = new StringBuilder();
		header.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
		.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\" xmlns:kml=\"http://www.opengis.net/kml/2.2\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n")
		.append("<Document><name>Trip log</name>\n")
		.append("<Style id=\"spot_green_normal\"><IconStyle><scale>0.5</scale><color>ff00ff00</color></IconStyle><LabelStyle><scale>0</scale></LabelStyle><Icon><href>http://img101.imageshack.us/img101/9273/spotm.png</href></Icon></Style>\n")
		.append("<Style id=\"spot_green_highlight\"><IconStyle><scale>0.5</scale><color>ff00ff00</color></IconStyle><LabelStyle><scale>0.6</scale></LabelStyle><Icon><href>http://img101.imageshack.us/img101/9273/spotm.png</href></Icon></Style>\n")
		.append("<StyleMap id=\"spot_green\"><Pair><key>normal</key><styleUrl>spot_green_normal</styleUrl></Pair><Pair><key>highlight</key><styleUrl>spot_green_highlight</styleUrl></Pair></StyleMap>\n")
		.append("<Style id=\"spot_yellow_normal\"><IconStyle><scale>0.6</scale><color>ff00ffff</color></IconStyle><LabelStyle><scale>0.5</scale></LabelStyle><Icon><href>http://img101.imageshack.us/img101/9273/spotm.png</href></Icon></Style>\n")
		.append("<Style id=\"spot_yellow_highlight\"><IconStyle><scale>0.6</scale><color>ff00ffff</color></IconStyle><LabelStyle><scale>0.6</scale></LabelStyle><Icon><href>http://img101.imageshack.us/img101/9273/spotm.png</href></Icon></Style>\n")
		.append("<StyleMap id=\"spot_yellow\"><Pair><key>normal</key><styleUrl>spot_yellow_normal</styleUrl></Pair><Pair><key>highlight</key><styleUrl>spot_yellow_highlight</styleUrl></Pair></StyleMap>\n")
		.append("<Style id=\"spot_red\"><IconStyle><scale>0.7</scale><color>ff0000ff</color></IconStyle><LabelStyle><scale>0.6</scale></LabelStyle><Icon><href>http://img101.imageshack.us/img101/9273/spotm.png</href></Icon></Style>\n");
		try {
			osw_kml.append(header);
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	public void kml_writeplacemark(){
		String gpsTime = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(gpsTimestamp);
		String localTime = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(System.currentTimeMillis());
		StringBuilder placemark = new StringBuilder();
		String style;
		if (cellid!=previouscellid){
			if (lac!=previouslac) {
				style="spot_red";
				previouslac=lac;
				previouscellid=cellid;
			}
			else{
				style="spot_yellow";
				previouscellid=cellid;
			}
		}else style="spot_green";
		placemark.append("<Placemark><name>").append(n_datapoints).append("</name>")
		.append("<styleUrl>" + style + "</styleUrl>")
		.append("<description><![CDATA[<ul>")
		.append("<li> Local Time: ").append(localTime)
		.append("</li><li>Local Timestamp: ").append(System.currentTimeMillis())
		.append("</li><li>GPS Time: ").append(gpsTime)
		.append("</li><li>GPS Timestamp: ").append(gpsTimestamp)
		.append("</li><li>GPS Speed: ").append(gpsSpeed)
		.append("</li><li>GPS Direction: ").append(gpsBearing)
		.append("</li><li>GPS Altitude: ").append(gpsAltitude)
		.append("</li><li>GPS Accuracy: ").append(gpsAccuracy)
		.append("</li><li>GPS Satellites: ").append(gpsSatellites)
		.append("</li><li>MCC-MNC: ").append(mcc + "-" + mnc)
		.append("</li><li>LAC-CellID: ").append(lac + "-" + cellid);
		if (rssi_logging)
			placemark.append("</li><li>RSSI: ").append(RSSI);
		placemark.append("</li><li>Network type: ").append(nettype)
		.append("</li><li>Data state: ").append(datastate)
		.append("</li><li>Data activity: ").append(dataactivity)
		.append("</li><li>Call state: ").append(callstate);
		if (neighbors_logging){
			placemark.append("</li><li>Neighboring cells: ").append(neighborsmapUMTS.size() + neighborsmapGSM.size()).append("<ol>");
			if (!neighborsmapUMTS.isEmpty()){
				for (Object key: neighborsmapUMTS.keySet()) {
					placemark.append("<li> PSC:").append(key).append(", RSCP:").append(neighborsmapUMTS.get(key)).append("</li>");
				}
			}
			if (!neighborsmapGSM.isEmpty()){
				for (Object key: neighborsmapGSM.keySet()) {
					placemark.append("<li> LAC-CID:").append(key).append(", RSSI:").append(neighborsmapGSM.get(key)).append("</li>");
				}
			}
			placemark.append("</ol>");
		}
		if (wifi_logging)
			if (wifiList!=null){
				placemark.append("</li><li>Wifi Access Points: ").append(String.valueOf(wifiList.size())).append("<ol>");
				for (int i=0; i<wifiList.size();i++){
					placemark.append("<li>SSID: ").append(wifiList.get(i).SSID)
					.append(" BSSID: ").append(wifiList.get(i).BSSID)
					.append("<br/>FREQ: ").append(wifiList.get(i).frequency)
					.append(" RSSI: ").append(wifiList.get(i).level)
					.append("<br/>ENCR: ");
					if (wifiList.get(i).capabilities.length()==0)
						placemark.append("[NONE]</li>");
					else placemark.append(wifiList.get(i).capabilities + "</li>");
				}
				placemark.append("</ol>");
			} else placemark.append("</li><li>Wifi Access Points: ").append("0");
		placemark.append("</li></ul>]]></description><Point>")
		.append("<coordinates>" + gpsLongitude + "," + gpsLatitude + "</coordinates>")
		.append("</Point></Placemark>\n");
		try {
			osw_kml.append(placemark);
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	public void kml_writetail(){
		try {
			osw_kml.append("</Document></kml>");
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	class WifiReceiver extends BroadcastReceiver{

		@Override
		public void onReceive(Context context, Intent intent) {
			wifiList = srvcWifiManager.getScanResults();
			if(continuous_wifi_scanning)
				srvcWifiManager.startScan();
		}
	}

}
