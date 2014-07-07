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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TabActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

public class MainActivity extends TabActivity implements OnSharedPreferenceChangeListener,OnCheckedChangeListener {
	static final private int WELCOME_DIALOG = 1;
	static final private int ENABLE_GPS_DIALOG = 2;
	static final private int NO_SDCARD_DIALOG = 3;
	static final private int ENABLE_WIFI_DIALOG = 4;
	
	private boolean isFirstTime;
	private boolean isLogging;
	private int minimumDistance;
	private int minimumSeconds;
	private long updateInterval;
	private boolean kml_export;
	private boolean rssi_logging;
	private boolean neighbors_logging;
	private boolean cellid_logging;
	private boolean wifi_logging;
	private boolean continuous_wifi_scanning;
	private String loggingSince;
//	private int n_datapoints;
	
	PowerManager powerManager;
	PowerManager.WakeLock wakeLock;
	LocationManager locationManager;
	TelephonyManager telephonyManager;
	WifiManager wifiManager;
	WifiReceiver wifiReceiver;
	
	SharedPreferences prefs;
	SharedPreferences.Editor prefEditor;
	
	Map<Integer,Integer> neighborsmapUMTS = new HashMap<Integer,Integer>();
	Map<String,Integer> neighborsmapGSM = new HashMap<String,Integer>();
	ToggleButton button;
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		prefEditor = prefs.edit();
		getUserPreferences();
		
		powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "RSSI_SCREEN_ON");
		locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		telephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
		wifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		
		if (isFirstTime){
			showDialog(WELCOME_DIALOG);
			prefEditor.putBoolean("firsttime", false);
			prefEditor.commit();
		}

		prefs.registerOnSharedPreferenceChangeListener(this);
		
		// Setting Tabs Views
		Resources res = getResources();
		TabHost tabHost = getTabHost();
		tabHost.addTab(tabHost.newTabSpec("Cellular").setIndicator("Network"
		,res.getDrawable(R.drawable.ic_tab_cellular)).setContent(R.id.cellulartabLayout));
		tabHost.addTab(tabHost.newTabSpec("GPS").setIndicator("location"
		,res.getDrawable(R.drawable.ic_tab_gps)).setContent(R.id.gpstabLayout));
		tabHost.addTab(tabHost.newTabSpec("Wifi").setIndicator("Wifi"
		,res.getDrawable(R.drawable.ic_tab_wifi)).setContent(R.id.wifitabLayout));
		tabHost.addTab(tabHost.newTabSpec("Log").setIndicator("Log"
		,res.getDrawable(R.drawable.ic_tab_log)).setContent(R.id.logtabLayout));
		tabHost.setCurrentTab(0);

		// initialize the toggle button
		button = (ToggleButton)findViewById(R.id.button_toggle);
		if (isLogging)
			button.setChecked(true);
		button.setOnCheckedChangeListener(this);
	}

	@Override
	public void onResume(){
		super.onResume();

		// Wifi broadcast Receiver
		wifiReceiver = new WifiReceiver();
		registerReceiver(wifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
		wifiManager.startScan();
		// Location Manager and Listener
		if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minimumSeconds * 1000, minimumDistance, locationListener);

		// Telephony Manager and Listener
		wakeLock.acquire();
		telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION |
					PhoneStateListener.LISTEN_DATA_ACTIVITY | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE |
					PhoneStateListener.LISTEN_SERVICE_STATE | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
					| PhoneStateListener.LISTEN_CALL_STATE);
		// Phone and SIM information are static, i.e. not updated regularly but only here
		updatePhoneSimInfo();
	}

	@Override
	public void onPause(){
		super.onPause();

		// When the app is not visible, unregister the listeners.
		unregisterReceiver(wifiReceiver);
		locationManager.removeUpdates(locationListener);
		telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
		wakeLock.release();
	}

	public void onCheckedChanged(CompoundButton buttonView, boolean becomesChecked){
		if (becomesChecked){
			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){
				if (!wifi_logging || wifiManager.isWifiEnabled()){
					if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
						//Everything was fine
						startService(new Intent(MainActivity.this, LoggingService.class));						
					} else{
						//GPS is OFF.
						showDialog(ENABLE_GPS_DIALOG);
					}
				}else{
					//WiFi is OFF
					button.setChecked(false);
					showDialog(ENABLE_WIFI_DIALOG);
				}
			}else{
				//SD-CARD Error
				button.setChecked(false);
				showDialog(NO_SDCARD_DIALOG);
			}
		} else {
			stopService(new Intent(MainActivity.this, LoggingService.class));
		}
	}
	
	@Override
	public Dialog onCreateDialog(int id) {
		switch(id) {
			case (WELCOME_DIALOG) :
				AlertDialog.Builder firstTimeDialog = new AlertDialog.Builder(this);
				firstTimeDialog.setTitle("Welcome in Mobilog")
					.setMessage(R.string.txt_welcomemessage)
					.setCancelable(false)
					.setPositiveButton("OK", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							//dialog.dismiss();
						}
					}
				);
				return firstTimeDialog.create();
			case (ENABLE_GPS_DIALOG) :
				AlertDialog.Builder enablegpsDialog = new AlertDialog.Builder(this);
				enablegpsDialog.setTitle("GPS is disabled")
					.setMessage(R.string.txt_enablegpsmessage)
					.setCancelable(true)
					.setPositiveButton("Proceed", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int id) {
								//dialog.dismiss();
								prefEditor.putBoolean("kml_export", false);
								prefEditor.commit();
								startService(new Intent(MainActivity.this, LoggingService.class));	
							}
					})
					.setNegativeButton("GPS Settings", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							button.setChecked(false);
							//dialog.dismiss();
							startActivityForResult(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS), 0);
						}
					})
					.setOnCancelListener(new DialogInterface.OnCancelListener() {
						public void onCancel(DialogInterface dialog) {
							button.setChecked(false);						
						}
					});
				return enablegpsDialog.create();
			case (NO_SDCARD_DIALOG) :
				AlertDialog.Builder nosdcardDialog = new AlertDialog.Builder(this);
				nosdcardDialog.setTitle("SD-CARD not found")
					.setMessage(R.string.txt_nosdcardmessage)
					.setCancelable(false)
					.setPositiveButton("OK", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							//dialog.dismiss();
						}
					}
				);
				return nosdcardDialog.create();
			case (ENABLE_WIFI_DIALOG) :
				AlertDialog.Builder enablewifiDialog = new AlertDialog.Builder(this);
				enablewifiDialog.setTitle("Wifi is disabled")
					.setMessage(R.string.txt_enablewifimessage)
					.setCancelable(true)
					.setPositiveButton("Wifi Settings", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							//dialog.dismiss();
							startActivityForResult(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), 0);
						}
					})
					.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int id) {
								//dialog.dismiss();
							}
					});
				return enablewifiDialog.create();

		}
		return null;
	}


	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,String key)
	{
//		if(key=="n_datapoints"){
			//update only n_datapoints
//			TextView n_datapoints_pref = (TextView)findViewById(R.id.n_datapoints);
//			n_datapoints = prefs.getInt("n_datapoints", 0);
//			n_datapoints_pref.setText(String.valueOf(n_datapoints));
//		} else 
			//update the whole tab
			getUserPreferences();
	}

	/** Gets preferences chosen by the user */
	public void getUserPreferences()
	{
		isLogging = prefs.getBoolean("isLogging", false);
		isFirstTime = prefs.getBoolean("firsttime", true);
		kml_export = prefs.getBoolean("kml_export", false);
		rssi_logging = prefs.getBoolean("rssi_logging",true);
		neighbors_logging = prefs.getBoolean("neighbors_logging", false);
		cellid_logging = prefs.getBoolean("cellid_logging", true);
//		n_datapoints = prefs.getInt("n_datapoints", 0);
		loggingSince = prefs.getString("loggingSince", null);
		wifi_logging = prefs.getBoolean("wifi_logging", false);
		continuous_wifi_scanning = prefs.getBoolean("continuous_wifi_scanning", false);
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
		updatePreferencesTab();
	}

	/** Fill the log TAB with the user-chosen options */
	private void updatePreferencesTab()
	{ 
		TextView rssi_logging_pref = (TextView)findViewById(R.id.rssi_logging);
		TextView neighbors_logging_pref = (TextView)findViewById(R.id.neighbors_logging);
		TextView cellid_logging_pref = (TextView)findViewById(R.id.cellid_logging);
		TextView wifi_logging_pref = (TextView)findViewById(R.id.wifi_logging);
		TextView continuous_wifi_scanning_pref = (TextView)findViewById(R.id.continuous_wifi_scanning);
		TextView export_as_pref = (TextView)findViewById(R.id.export_as);
		TextView update_interval_seconds_pref = (TextView)findViewById(R.id.update_interval_seconds);
		TextView update_interval_meters_pref = (TextView)findViewById(R.id.update_interval_meters);
		TextView update_interval_pref = (TextView)findViewById(R.id.update_interval);
		TextView loggingSince_pref = (TextView)findViewById(R.id.loggingSince);
//		TextView n_datapoints_pref = (TextView)findViewById(R.id.n_datapoints);
		
		if(rssi_logging)	
			rssi_logging_pref.setText("yes");
		else rssi_logging_pref.setText("no");
		if(neighbors_logging)	
			neighbors_logging_pref.setText("yes");
		else neighbors_logging_pref.setText("no");
		if(cellid_logging)
			cellid_logging_pref.setText("yes");
		else cellid_logging_pref.setText("no");
		if(kml_export)
			export_as_pref.setText("csv + kml");
		else export_as_pref.setText("csv");
		if(wifi_logging)
			wifi_logging_pref.setText("yes");
		else wifi_logging_pref.setText("no");
		if(continuous_wifi_scanning)
			continuous_wifi_scanning_pref.setText("yes");
		else continuous_wifi_scanning_pref.setText("no");
		if(loggingSince!=null)
			loggingSince_pref.setText(loggingSince);
		else loggingSince_pref.setText("<press start>");

//		n_datapoints_pref.setText(String.valueOf(n_datapoints));
		update_interval_seconds_pref.setText(String.valueOf(minimumSeconds));
		update_interval_meters_pref.setText(String.valueOf(minimumDistance));
		update_interval_pref.setText(String.valueOf(updateInterval));
	
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	/** Called when one option in the menu button is selected. */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
		case R.id.settings:
			Intent I = new Intent (this, PreferencesActivity.class);
			startActivity(I);
			return true;
		case R.id.about:
			showDialog(WELCOME_DIALOG);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	private void updateWithNewLocation(Location location){
		TextView mylocaltimestamp = (TextView)findViewById(R.id.mylocaltimestamp);
		TextView mygpstimestamp = (TextView)findViewById(R.id.mygpstimestamp);
		TextView mygpslatitude = (TextView)findViewById(R.id.mygpslatitude);
		TextView mygpslongitude = (TextView)findViewById(R.id.mygpslongitude);
		TextView mygpsaltitude = (TextView)findViewById(R.id.mygpsaltitude);
		TextView mygpsspeed = (TextView)findViewById(R.id.mygpsspeed);
		TextView mygpsdirection = (TextView)findViewById(R.id.mygpsdirection);
		TextView mygpsaccuracy = (TextView)findViewById(R.id.mygpsaccuracy);
		TextView mygpssatellites = (TextView)findViewById(R.id.mygpssatellites);			

		if (location!=null){
			mylocaltimestamp.setText(String.valueOf(System.currentTimeMillis()));
			mygpstimestamp.setText(String.valueOf(location.getTime()));
			mygpslatitude.setText(String.valueOf(location.getLatitude()));
			mygpslongitude.setText(String.valueOf(location.getLongitude()));
			mygpsaltitude.setText(String.valueOf(location.getAltitude()));
			mygpsspeed.setText(String.valueOf(location.getSpeed()));
			mygpsdirection.setText(String.valueOf(location.getBearing()));
			mygpsaccuracy.setText(String.valueOf(location.getAccuracy()));
			mygpssatellites.setText(String.valueOf(location.getExtras().getInt("satellites")));
		} else {
			mylocaltimestamp.setText(String.valueOf(System.currentTimeMillis()));
			mygpstimestamp.setText("NA");
			mygpslatitude.setText("NA");
			mygpslongitude.setText("NA");
			mygpsaltitude.setText("NA");
			mygpsspeed.setText("NA");
			mygpsdirection.setText("NA");
			mygpsaccuracy.setText("NA");
			mygpssatellites.setText("NA");
		}
	}
	
	/** GPS Listener */
	private final LocationListener locationListener = new LocationListener() {
		public void onLocationChanged(Location location){updateWithNewLocation(location);}
		public void onProviderDisabled(String Provider){updateWithNewLocation(null);}
		public void onProviderEnabled(String provider){}
		public void onStatusChanged(String provider, int status, Bundle extras) {
			if (status == LocationProvider.OUT_OF_SERVICE || status == LocationProvider.TEMPORARILY_UNAVAILABLE) 
				updateWithNewLocation(null);
		}
	};

	private void updatePhoneSimInfo(){
		TextView myphonetype = (TextView)findViewById(R.id.myphonetype);
		TextView myphoneid = (TextView)findViewById(R.id.myphoneid);
		TextView sim_state = (TextView)findViewById(R.id.sim_state);
		TextView sim_mcc = (TextView)findViewById(R.id.sim_mcc);
		TextView sim_mnc = (TextView)findViewById(R.id.sim_mnc);
		TextView sim_country = (TextView)findViewById(R.id.sim_country);
		TextView sim_operator = (TextView)findViewById(R.id.sim_operator);
		TextView sim_msisdn = (TextView)findViewById(R.id.sim_msisdn);
		TextView sim_sn = (TextView)findViewById(R.id.sim_sn);
		TextView sim_subid = (TextView)findViewById(R.id.sim_subid);

		if (telephonyManager != null){
			/**Phone type (CDMA vs GSM).*/ 
			int phoneType = telephonyManager.getPhoneType();
			switch (phoneType) {
				case (TelephonyManager.PHONE_TYPE_CDMA): myphonetype.setText("CDMA2000/1xRTT/EVDO"); break;
				case (TelephonyManager.PHONE_TYPE_GSM): myphonetype.setText("GSM/UMTS/HSPA"); break;
				case (TelephonyManager.PHONE_TYPE_NONE): myphonetype.setText("NONE"); break;
				default: break;
			}

			/**Phone IMEI (or MEID in case of CDMA)*/
			String deviceId = telephonyManager.getDeviceId();
			if (deviceId != null){
				myphoneid.setText(telephonyManager.getDeviceId());
			}

			/**SIM infos. 
			 * We can read from the sim only if the sim is in READY, otherwise crash*/
			int simState = telephonyManager.getSimState();
			switch (simState) {
				case (TelephonyManager.SIM_STATE_ABSENT): sim_state.setText("SIM ABSENT"); break;
				case (TelephonyManager.SIM_STATE_NETWORK_LOCKED): sim_state.setText("Network locked"); break;
				case (TelephonyManager.SIM_STATE_PIN_REQUIRED): sim_state.setText("PIN Required"); break;
				case (TelephonyManager.SIM_STATE_PUK_REQUIRED): sim_state.setText("PUK Required"); break;
				case (TelephonyManager.SIM_STATE_UNKNOWN): sim_state.setText("UNKNOWN"); break;
				case (TelephonyManager.SIM_STATE_READY): {
					sim_state.setText("READY");
					String mcc = telephonyManager.getSimOperator().substring(0,3);
					String mnc = telephonyManager.getSimOperator().substring(3);
					String country = telephonyManager.getSimCountryIso();
					String operator = telephonyManager.getSimOperatorName();
					String simSerialNumber = telephonyManager.getSimSerialNumber();
					String simSubscriberId = telephonyManager.getSubscriberId();
					String line1Number = telephonyManager.getLine1Number();
					if (mcc!=null) sim_mcc.setText(mcc);
					if (mnc!=null) sim_mnc.setText(mnc);
					if (country!=null) sim_country.setText(country);
					if (operator!=null) sim_operator.setText(operator);
					if (simSerialNumber!=null) sim_sn.setText(simSerialNumber);
					if (simSubscriberId != null) sim_subid.setText(simSubscriberId);
					if (line1Number != null) sim_msisdn.setText(line1Number);
				}
				default: break;
			}
		} else {
			myphonetype.setText("NA");
			myphoneid.setText("NA");
			sim_state.setText("NA");
			sim_mcc.setText("NA");
			sim_mnc.setText("NA");
			sim_country.setText("NA");
			sim_operator.setText("NA");
			sim_msisdn.setText("NA");
			sim_sn.setText("NA");
			sim_subid.setText("NA");

		}
	};
	/*EDIT BY andr3jx - DATE: 07.07.2014*/
	public int mcc;
	public int mnc;
	/* END EDIT*/
	
	private void updateNetworkData(CellLocation location){
		TextView mynetcountry = (TextView)findViewById(R.id.mynetcountry);
		TextView mynetoperator = (TextView)findViewById(R.id.mynetoperator);
		TextView mynetmcc = (TextView)findViewById(R.id.mynetmcc);
		TextView mynetmnc = (TextView)findViewById(R.id.mynetmnc);
		TextView mynettype = (TextView)findViewById(R.id.mynettype);
		TextView mylac = (TextView)findViewById(R.id.mylac);
		TextView mycid = (TextView)findViewById(R.id.mycid);
		TextView mygmapsloc = (TextView)findViewById(R.id.mygmapsloc);
		
		if (location != null) {
			/**The country iso code and the operator name as read from the network*/
			mynetcountry.setText(telephonyManager.getNetworkCountryIso());
			mynetoperator.setText(telephonyManager.getNetworkOperatorName());
			/**The type of network.
			 * Actually I do not support American handsets (1xRTT, EVDO_0, and EVDO_A).
			 * But I show it here anyway. :-) Who knows... */
			int nettype = telephonyManager.getNetworkType();
			switch (nettype) {
				case (TelephonyManager.NETWORK_TYPE_1xRTT): mynettype.setText("1xRTT"); break;
				case (TelephonyManager.NETWORK_TYPE_CDMA): mynettype.setText("CDMA"); break;
				case (TelephonyManager.NETWORK_TYPE_EDGE): mynettype.setText("EDGE"); break;
				case (TelephonyManager.NETWORK_TYPE_EVDO_0): mynettype.setText("EVDO_0"); break;
				case (TelephonyManager.NETWORK_TYPE_EVDO_A): mynettype.setText("EVDO_A"); break;
				case (TelephonyManager.NETWORK_TYPE_HSDPA): mynettype.setText("HSDPA"); break;
				case (TelephonyManager.NETWORK_TYPE_HSPA): mynettype.setText("HSPA"); break;
				case (TelephonyManager.NETWORK_TYPE_HSUPA): mynettype.setText("HSUPA"); break;
				case (TelephonyManager.NETWORK_TYPE_UMTS): mynettype.setText("UMTS"); break;
				case (TelephonyManager.NETWORK_TYPE_UNKNOWN): mynettype.setText("UNKNOWN"); break;
				default: break;
			}
	
			/** MCC and MNC. the check is required, otherwise crash when no coverage is available*/
			String mynetmccmnc = telephonyManager.getNetworkOperator();
			if (mynetmccmnc != null && mynetmccmnc.length() >= 4) {
				mynetmcc.setText(mynetmccmnc.substring(0,3));
				mynetmnc.setText(mynetmccmnc.substring(3));
			} else {
				mynetmcc.setText("NA");
				mynetmnc.setText("NA");
			}
	
			/** LAC and Cellid*/
			GsmCellLocation gsmLocation = (GsmCellLocation)location;
			int lac = gsmLocation.getLac();
			int cid = gsmLocation.getCid() & 0xffff;
			if (lac!=-1 && lac !=65535)
				mylac.setText(String.valueOf(lac));
			else mylac.setText("NA");
			if (cid!=-1 && cid != 65535)
				mycid.setText(String.valueOf(cid));
			else mycid.setText("NA");
			/*EDIT BY andr3jx - DATE: 07.07.2014*/
			mcc = Integer.parseInt(mynetmccmnc.substring(0,3));
			mnc = Integer.parseInt(mynetmccmnc.substring(3));

			if(gmapsdata(cid,lac, mcc, mnc)==true){
				mygmapsloc.setText(String.valueOf(latdata) + ", " + String.valueOf(londata));			
			}
			/*END EDIT*/
			
		} else {
			mynetcountry.setText("NA");
			mynetoperator.setText("NA");
			mynetmcc.setText("NA");
			mynetmnc.setText("NA");
			mynettype.setText("NA");
			mylac.setText("NA");
			mycid.setText("NA");
		}
	};
	
	/*
	 * EDIT BY andr3jx - DATE: 07.07.2014
	 * GMAPS LOCATION
	 * 
	 */

	double latdata;
	double londata;
	private boolean gmapsdata(int cellID, int lac, int mcc, int mnc) {
       // logger.info("Requesting position for cellID: " + cellID + " using google database");

        try {
                URL providerAddress = new URL("http://www.google.com/glm/mmap");
                HttpURLConnection connection = (HttpURLConnection) providerAddress.openConnection();

                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.connect();

                OutputStream outputStream = connection.getOutputStream();
                writePlainData(outputStream, cellID, lac, mcc, mnc);

                InputStream inputStream = connection.getInputStream();
                DataInputStream dataInputStream = new DataInputStream(inputStream);

                dataInputStream.readShort();
                dataInputStream.readByte();

                int code = dataInputStream.readInt();
                if (code == 0) {
                        double lat = (double) dataInputStream.readInt() / 1000000D;
                        double lon = (double) dataInputStream.readInt() / 1000000D;
                        latdata=lat;
                        londata=lon;

                        return true;
                } else {
                        return false;
                }

        } catch (Exception e) {

        }
        return false;
}
	
	private void writePlainData(OutputStream out, int cellID, int lac, int mcc, int mnc) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeShort(0x0E); // Fct code

        dos.writeInt(0); // requesting 8 byte session
        dos.writeInt(0);

        dos.writeShort(0); // country code string
        dos.writeShort(0); // client descriptor string
        dos.writeShort(0); // version tag string

        dos.writeByte(0x1B); // Fct code

        dos.writeInt(0); // MNC?
        dos.writeInt(0); // MCC?
        dos.writeInt(3); // Radio Access Type (3=GSM, 5=UMTS)

        dos.writeShort(0); // length of provider name

        // provider name string
        dos.writeInt(cellID); // CID
        dos.writeInt(lac); // LAC
        dos.writeInt(mnc); // MNC
        dos.writeInt(mcc); // MCC
        dos.writeInt(-1); // always -1
        dos.writeInt(0); // rx level

        dos.flush();
}
	
/*
 * 
 * END EDIT
 * 	
 */
	
	private void updateSignalStrengths(SignalStrength signalStrength){
		/** Current cell */
		TextView cellSignalStrength_Asu_dBm = (TextView)findViewById(R.id.cellsignalstrength_Asu_dBm);
		TextView cellBitErrorRate = (TextView)findViewById(R.id.cellbiterrorrate);
		if (signalStrength!=null){
			int asu = signalStrength.getGsmSignalStrength();
			cellSignalStrength_Asu_dBm.setText(String.valueOf(asu)+"/"+String.valueOf(-113+(2*asu)));
			cellBitErrorRate.setText(String.valueOf(signalStrength.getGsmBitErrorRate()));
		} else {
			cellSignalStrength_Asu_dBm.setText("NA/NA");
			cellBitErrorRate.setText("NA");
		}
	}

	private void updateNeighboringCells(){
		TextView celln_neighbors = (TextView)findViewById(R.id.celln_neighbors);
		TextView cellneighbors = (TextView)findViewById(R.id.cellneighbors);
		if (telephonyManager!=null) {
			/** Neighboring cells */
			List<NeighboringCellInfo> neighboringCellInfo;
			neighboringCellInfo = telephonyManager.getNeighboringCellInfo();
			
			/** Fill the hash tables depending on the network type*/
			for (NeighboringCellInfo i : neighboringCellInfo) {
				int networktype=i.getNetworkType();
				if ((networktype == TelephonyManager.NETWORK_TYPE_UMTS) || 
						(networktype == TelephonyManager.NETWORK_TYPE_HSDPA) ||
						(networktype == TelephonyManager.NETWORK_TYPE_HSUPA) ||
						(networktype == TelephonyManager.NETWORK_TYPE_HSPA))
					neighborsmapUMTS.put(i.getPsc(), i.getRssi()-115);
				else 
					if(gmapsdata(i.getCid(),i.getLac(), mcc, mnc)==true){
						neighborsmapGSM.put(i.getLac()+"-"+i.getCid()+ ", " + "Location: " + String.valueOf(latdata) + ", " + String.valueOf(londata) + "; ", -113+(2*(i.getRssi())));		
					}else{
					neighborsmapGSM.put(i.getLac()+"-"+i.getCid(), -113+(2*(i.getRssi())));
					}
			}
	
			/** Print the number of current visible neighbors + observed cell neighbors after the last cell reselection*/
			celln_neighbors.setText(String.valueOf(neighboringCellInfo.size()) + "/" + String.valueOf(neighborsmapUMTS.size()+neighborsmapGSM.size()));
			
			/** Write everything on the screen */
			StringBuilder sb = new StringBuilder();
			int i=1;
			if (!neighborsmapUMTS.isEmpty())
				for (Object key: neighborsmapUMTS.keySet()) {
					sb.append(i)
					.append(") PSC: ")
					.append(key)
					.append(" RSCP: ")
					.append(neighborsmapUMTS.get(key)) //TS25.133 section 9.1.1.3
					.append(" dBm");
					if (i < neighborsmapUMTS.size() + neighborsmapGSM.size())
						sb.append("\n");
					i++;
				}
			if (!neighborsmapGSM.isEmpty())
				for (Object key: neighborsmapGSM.keySet()) {
					sb.append(i)
					.append(") LAC-CID: ")
					.append(key)
					.append(" RSSI: ")
					.append(neighborsmapGSM.get(key)) //TS27.007 section 8.5
					.append(" dBm");
					if (i < neighborsmapUMTS.size() + neighborsmapGSM.size())
						sb.append("\n");
					i++;
				}
			cellneighbors.setText(sb);
		} else {
			celln_neighbors.setText("");
			cellneighbors.setText("");
		}
	}
	/**Telephony Listener*/
	PhoneStateListener phoneStateListener = new PhoneStateListener() {
		public void onCellLocationChanged(CellLocation location){
			updateNetworkData(location);
			neighborsmapGSM.clear(); //clean the list of neighbors (GSM networks)
			neighborsmapUMTS.clear(); //clean the list of neighbors (UMTS networks)
			/*
			 * EDIT BY andr3jx - DATE: 07.07.2014
			 * */
			updateNeighboringCells(); 
			/*
			 * END EDIT
			 */
			
		}
		public void onSignalStrengthsChanged(SignalStrength signalStrength){
			updateSignalStrengths(signalStrength); //update RSSI for current cell
			for (String key: neighborsmapGSM.keySet())  
				neighborsmapGSM.put(key,0); // Reinitialize the RSSI of all the neighbors
			for (int key: neighborsmapUMTS.keySet())
				neighborsmapUMTS.put(key,0); // Reinitialize the RSSI of all the neighbors
			updateNeighboringCells(); //update the RSSI of the existing neighbors
		}
		public void onDataActivity(int direction){
			TextView mynetdataactivity = (TextView)findViewById(R.id.mynetdataactivity);
			switch(direction) {
				case TelephonyManager.DATA_ACTIVITY_IN: mynetdataactivity.setText("IN"); break;
				case TelephonyManager.DATA_ACTIVITY_OUT: mynetdataactivity.setText("OUT"); break;
				case TelephonyManager.DATA_ACTIVITY_INOUT: mynetdataactivity.setText("INOUT"); break;
				case TelephonyManager.DATA_ACTIVITY_NONE: mynetdataactivity.setText("NONE"); break;
			} 
		}
		public void onDataConnectionStateChanged(int state){
			TextView mynetdatastate = (TextView)findViewById(R.id.mynetdatastate);
			switch(state){
				case TelephonyManager.DATA_CONNECTED: mynetdatastate.setText("Connected"); break;
				case TelephonyManager.DATA_CONNECTING: mynetdatastate.setText("Connecting"); break;
				case TelephonyManager.DATA_DISCONNECTED: mynetdatastate.setText("Disconnected"); break;
				case TelephonyManager.DATA_SUSPENDED: mynetdatastate.setText("Suspended"); break;
			}
		}
		public void onServiceStateChanged(ServiceState serviceState)
		{
			TextView myservicestate = (TextView)findViewById(R.id.myservicestate);
			int state = serviceState.getState();
			switch(state){
				case ServiceState.STATE_EMERGENCY_ONLY: myservicestate.setText("EMERGENCY ONLY"); break;
				case ServiceState.STATE_IN_SERVICE: myservicestate.setText("IN SERVICE"); break;
				case ServiceState.STATE_OUT_OF_SERVICE: myservicestate.setText("OUT OF SERVICE"); break;
				case ServiceState.STATE_POWER_OFF: myservicestate.setText("POWER OFF"); break;
			}
		}
		public void onCallStateChanged(int state, String incomingNumber){
			TextView mycallstate = (TextView)findViewById(R.id.mycallstate);
			switch(state){
				case TelephonyManager.CALL_STATE_IDLE: mycallstate.setText("IDLE"); break;
				case TelephonyManager.CALL_STATE_OFFHOOK: mycallstate.setText("OFFHOOK"); break;
				case TelephonyManager.CALL_STATE_RINGING: mycallstate.setText("RINGING"); break;	
			}
		}
	};

	
//	private void cleardata()
//	{
//		TextView celln_neighbors = (TextView)findViewById(R.id.celln_neighbors);
//		TextView cellneighbors = (TextView)findViewById(R.id.cellneighbors);
//		TextView cellSignalStrength_Asu_dBm = (TextView)findViewById(R.id.cellsignalstrength_Asu_dBm);
//		TextView cellBitErrorRate = (TextView)findViewById(R.id.cellbiterrorrate);
//		TextView mynetcountry_operator = (TextView)findViewById(R.id.mynetcountry_operator);
//		TextView mynetmccmnc = (TextView)findViewById(R.id.mynetmccmnc);
//		TextView mynettype = (TextView)findViewById(R.id.mynettype);
//		TextView mylaccid = (TextView)findViewById(R.id.mylaccid);
//		TextView myphonetype = (TextView)findViewById(R.id.myphonetype);
//		TextView myphoneid = (TextView)findViewById(R.id.myphoneid);
//		TextView sim_state = (TextView)findViewById(R.id.sim_state);
//		TextView sim_country_operator = (TextView)findViewById(R.id.sim_country_operator);
//		TextView sim_mccmnc = (TextView)findViewById(R.id.sim_mccmnc);
//		TextView sim_msisdn = (TextView)findViewById(R.id.sim_msisdn);
//		TextView sim_sn = (TextView)findViewById(R.id.sim_sn);
//		TextView sim_subid = (TextView)findViewById(R.id.sim_subid);
//		TextView gpstime = (TextView)findViewById(R.id.gpstime);
//		TextView mylatitude = (TextView)findViewById(R.id.mylatitude);
//		TextView mylongitude = (TextView)findViewById(R.id.mylongitude);
//		TextView myaltitude = (TextView)findViewById(R.id.myaltitude);
//		TextView myspeed = (TextView)findViewById(R.id.myspeed);
//		TextView mydirection = (TextView)findViewById(R.id.mydirection);
//		TextView accuracy = (TextView)findViewById(R.id.gpsaccuracy);
//		TextView mynetdataactivity = (TextView)findViewById(R.id.mynetdataactivity);
//		TextView mynetdatastate = (TextView)findViewById(R.id.mynetdatastate);
//		TextView myservicestate = (TextView)findViewById(R.id.myservicestate);
//		TextView mycallstate = (TextView)findViewById(R.id.mycallstate);
//		celln_neighbors.setText("");
//		cellneighbors.setText("");
//		cellSignalStrength_Asu_dBm.setText("");
//		cellBitErrorRate.setText("");
//		mynetcountry_operator.setText("");
//		mynetmccmnc.setText("");
//		mynettype.setText("");
//		mylaccid.setText("");
//		myphonetype.setText("");
//		myphoneid.setText("");
//		sim_state.setText("");
//		sim_country_operator.setText("");
//		sim_mccmnc.setText("");
//		sim_msisdn.setText("");
//		sim_sn.setText("");
//		sim_subid.setText("");
//		gpstime.setText("");
//		mylatitude.setText("");
//		mylongitude.setText("");
//		myaltitude.setText("");
//		myspeed.setText("");
//		mydirection.setText("");
//		accuracy.setText("");
//		mynetdataactivity.setText("");
//		mynetdatastate.setText("");
//		myservicestate.setText("");
//		mycallstate.setText("");
//	}

	class WifiReceiver extends BroadcastReceiver{

		@Override
		public void onReceive(Context context, Intent intent) {
			List<ScanResult> wifiList;
			StringBuilder sb = new StringBuilder();
			wifiList = wifiManager.getScanResults();
			for (int i=0; i<wifiList.size();i++){
				sb.append("\n" + (i+1))
				.append(")\tSSID:").append(wifiList.get(i).SSID)
				.append(", BSSID:").append(wifiList.get(i).BSSID)
				.append(", FREQ:").append(wifiList.get(i).frequency)
				.append(", RSSI:").append(wifiList.get(i).level)
				.append(", ENCR:");
				if (wifiList.get(i).capabilities.length()==0)
					sb.append("[NONE]");
				else sb.append(wifiList.get(i).capabilities);
			}
			TextView hotspotslist = (TextView)findViewById(R.id.hotspotslist);
			hotspotslist.setText(sb);
			TextView nhotspots = (TextView)findViewById(R.id.nhotspots);
			nhotspots.setText(String.valueOf(wifiList.size()));
			if(continuous_wifi_scanning)
				wifiManager.startScan();
		}
	}
	
}