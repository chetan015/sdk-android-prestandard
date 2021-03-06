/*
 * Copyright (c) 2020 Ubique Innovation AG <https://www.ubique.ch>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */
package org.dpppt.android.sdk.internal;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;

import org.dpppt.android.sdk.DP3T;
import org.dpppt.android.sdk.R;
import org.dpppt.android.sdk.TracingStatus;
import org.dpppt.android.sdk.internal.crypto.CryptoModule;
import org.dpppt.android.sdk.internal.crypto.EphId;
import org.dpppt.android.sdk.internal.database.Database;
import org.dpppt.android.sdk.internal.database.models.BtLocToken;
import org.dpppt.android.sdk.internal.database.models.DeviceLocation;
import org.dpppt.android.sdk.internal.gatt.BleClient;
import org.dpppt.android.sdk.internal.gatt.BleServer;
import org.dpppt.android.sdk.internal.gatt.BluetoothServiceStatus;
import org.dpppt.android.sdk.internal.gatt.BluetoothState;
import org.dpppt.android.sdk.internal.logger.Logger;

import java.util.ArrayList;
import java.util.Collection;

import static org.dpppt.android.sdk.internal.AppConfigManager.DEFAULT_SCAN_DURATION;
import static org.dpppt.android.sdk.internal.AppConfigManager.DEFAULT_SCAN_INTERVAL;
import static org.dpppt.android.sdk.internal.util.Base64Util.toBase64;

public class TracingService extends Service {

	private static final String TAG = "TracingService";

	public static final String ACTION_START = TracingService.class.getCanonicalName() + ".ACTION_START";
	public static final String ACTION_RESTART_CLIENT = TracingService.class.getCanonicalName() + ".ACTION_RESTART_CLIENT";
	public static final String ACTION_RESTART_SERVER = TracingService.class.getCanonicalName() + ".ACTION_RESTART_SERVER";
	public static final String ACTION_STOP = TracingService.class.getCanonicalName() + ".ACTION_STOP";

	public static final String EXTRA_ADVERTISE = TracingService.class.getCanonicalName() + ".EXTRA_ADVERTISE";
	public static final String EXTRA_RECEIVE = TracingService.class.getCanonicalName() + ".EXTRA_RECEIVE";
	public static final String EXTRA_SCAN_INTERVAL = TracingService.class.getCanonicalName() + ".EXTRA_SCAN_INTERVAL";
	public static final String EXTRA_SCAN_DURATION = TracingService.class.getCanonicalName() + ".EXTRA_SCAN_DURATION";

	private static final String NOTIFICATION_CHANNEL_ID = "dp3t_tracing_service";
	private static final int NOTIFICATION_ID = 1827;

	private Handler handler;
	private PowerManager.WakeLock wl;



	private Database database;

	private BleServer bleServer;
	private BleClient bleClient;
	private LocationService locationService;


	private Runnable periodicLocationUpdatesRunnable;

	private final BroadcastReceiver bluetoothStateChangeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
				if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_ON) {
					Logger.w(TAG, BluetoothAdapter.ACTION_STATE_CHANGED);
					BluetoothServiceStatus.resetInstance();
					BroadcastHelper.sendErrorUpdateBroadcast(context);
				}
			}
		}
	};

	private final BroadcastReceiver locationServiceStateChangeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) {
				Logger.w(TAG, LocationManager.MODE_CHANGED_ACTION);
				BroadcastHelper.sendErrorUpdateBroadcast(context);
			}
		}
	};

	private final BroadcastReceiver errorsUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (BroadcastHelper.ACTION_UPDATE_ERRORS.equals(intent.getAction())) {
				invalidateForegroundNotification();
			}
		}
	};

	private boolean startAdvertising;
	private boolean startReceiving;
	private boolean startTracking;
	private long scanInterval;
	private long scanDuration;

	private boolean isFinishing;
	private long locationInterval = 10000;//300000; // 5 minutes

	public TracingService() { }

	@Override
	public void onCreate() {
		super.onCreate();

		isFinishing = false;

		IntentFilter bluetoothFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		registerReceiver(bluetoothStateChangeReceiver, bluetoothFilter);

		IntentFilter locationServiceFilter = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
		registerReceiver(locationServiceStateChangeReceiver, locationServiceFilter);

		IntentFilter errorsUpdateFilter = new IntentFilter(BroadcastHelper.ACTION_UPDATE_ERRORS);
		registerReceiver(errorsUpdateReceiver, errorsUpdateFilter);

		database = new Database(getApplicationContext());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getAction() == null) {
			stopSelf();
			return START_NOT_STICKY;
		}

		if (wl == null) {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
					getPackageName() + ":TracingServiceWakeLock");
			wl.acquire();
		}

		Logger.i(TAG, "onStartCommand() with " + intent.getAction());

		scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
		scanDuration = intent.getLongExtra(EXTRA_SCAN_DURATION, DEFAULT_SCAN_DURATION);

		startAdvertising = intent.getBooleanExtra(EXTRA_ADVERTISE, true);
		startReceiving = intent.getBooleanExtra(EXTRA_RECEIVE, true);
		startTracking = true;

		if (ACTION_START.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			start();
		} else if (ACTION_RESTART_CLIENT.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			ensureStarted();
			restartClient();
		} else if (ACTION_RESTART_SERVER.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			ensureStarted();
			restartServer();
		} else if (ACTION_STOP.equals(intent.getAction())) {
			stopForegroundService();
		}

		return START_REDELIVER_INTENT;
	}

	private Notification createForegroundNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel();
		}

		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		PendingIntent contentIntent = null;
		if (launchIntent != null) {
			contentIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		}

		TracingStatus status = DP3T.getStatus(this);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
				.setOngoing(true)
				.setSmallIcon(R.drawable.ic_handshakes)
				.setContentIntent(contentIntent);

		if (status.getErrors().size() > 0) {
			String errorText = getNotificationErrorText(status.getErrors());
			builder.setContentTitle(getString(R.string.dp3t_sdk_service_notification_title))
					.setContentText(errorText)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(errorText))
					.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		} else {
			String text = getString(R.string.dp3t_sdk_service_notification_text);
			builder.setContentTitle(getString(R.string.dp3t_sdk_service_notification_title))
					.setContentText(text)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
					.setPriority(NotificationCompat.PRIORITY_LOW)
					.build();
		}

		return builder.build();
	}

	private String getNotificationErrorText(Collection<TracingStatus.ErrorState> errors) {
		StringBuilder sb = new StringBuilder(getString(R.string.dp3t_sdk_service_notification_errors)).append("\n");
		String sep = "";
		for (TracingStatus.ErrorState error : errors) {
			sb.append(sep).append(getString(error.getErrorString()));
			sep = ", ";
		}
		return sb.toString();
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private void createNotificationChannel() {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		String channelName = getString(R.string.dp3t_sdk_service_notification_channel);
		NotificationChannel channel =
				new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		notificationManager.createNotificationChannel(channel);
	}

	private void invalidateForegroundNotification() {
		if (isFinishing) {
			return;
		}

		Notification notification = createForegroundNotification();
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(NOTIFICATION_ID, notification);
	}

	private void start() {
		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
		handler = new Handler();

		invalidateForegroundNotification();
		restartTrackingLocation();
		restartClient();
		restartServer();
	}

	private void ensureStarted() {
		if (handler == null) {
			handler = new Handler();
		}
		invalidateForegroundNotification();
	}

	protected boolean checkPermissions(){return true;}

	protected void restartTrackingLocation(){
		boolean isTrackingLocation = startTrackingLocation();
		if(!isTrackingLocation){
			Logger.e(TAG,"location tracking not started");
		}
	}
	protected boolean startTrackingLocation(){
		stopTrackingLocation();
		boolean isTracking = false;
		// TODO check for appropriate permissions before starting location updates
		if(checkPermissions() && startTracking) {
			locationService = LocationService.getInstance(getApplicationContext());
			Logger.d(TAG, "startTrackingLocation");
			if(!locationService.isLocationUpdatesEnabled()) {
				isTracking = locationService.startLocationUpdates();
			}
			periodicLocationUpdatesRunnable = new Runnable() {
				@Override
				public void run() {
					Log.d("runnableSaveLocation", "Called on main thread");
					onLocationUpdate(locationService.getLastLocation());
					handler.postDelayed(this, locationInterval);
				}
			};
			// schedule the onLocationUpdate function
			handler.post(periodicLocationUpdatesRunnable);

		}

		return isTracking;
	}
	protected void stopTrackingLocation(){
		if(locationService != null){
			locationService.stopLocationUpdates();
			handler.removeCallbacks(periodicLocationUpdatesRunnable);
			locationService = null;
		}
	}
//	protected void startLocationUpdates(){
////		// Create the location request to start receiving updates
////		mLocationRequest = new LocationRequest();
////		mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
////		mLocationRequest.setInterval(LOCATION_INTERVAL);
////		mLocationRequest.setFastestInterval(FASTEST_INTERVAL);
////
////		// Create LocationSettingsRequest object using location request
////		LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
////		builder.addLocationRequest(mLocationRequest);
////		LocationSettingsRequest locationSettingsRequest = builder.build();
////		// Check whether location settings are satisfied
////		// https://developers.google.com/android/reference/com/google/android/gms/location/SettingsClient
////		SettingsClient settingsClient = LocationServices.getSettingsClient(this);
////		settingsClient.checkLocationSettings(locationSettingsRequest);
////
////		fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
////		mLocationCallback = new LocationCallback(){
////			// Callback function onLocationUpdate called when a location update is received from API
////			@Override
////			public void onLocationResult(LocationResult locationResult) {
////				// do work here
////				onLocationUpdate(locationResult.getLastLocation());
////			}
////		};
////		try{
////			fusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback , Looper.myLooper());
////		}
////		catch(SecurityException e){
////			System.out.println("Not Enough permissions.");
////		}
//		mLocationCallback = new LocationCallback(){
//			// Callback function onLocationUpdate called when a location update is received from API
//			@Override
//			public void onLocationResult(LocationResult locationResult) {
//				// do work here
//				Location location = locationResult.getLastLocation();
//				if(location != null)
//				{
//					// New location has now been determined
//					DeviceLocation deviceLocation = new DeviceLocation(location.getTime(),location.getLatitude(),location.getLongitude());
//					String msg = "Updated Location: " +
//							location.getLatitude() + "," +
//							location.getLongitude();
////		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
//					// You can now create a LatLng Object for use with maps
//					System.out.println("Recorded DeviceLocation: "+ deviceLocation.toString());
//					//Saving to Database
//					System.out.println("Saving to Database");
//					database.saveDeviceLocation(deviceLocation);
//					// Save location to database
//					System.out.println("Verifying database store:");
//					ArrayList<DeviceLocation> deviceLocations = database.getDeviceLocations();
//					for(DeviceLocation dloc:deviceLocations){
//						System.out.println(dloc.toString());
//					}
//				}
//				else{
//				}
//			}
//		};
//
//		LocationService.getInstance(this).startLocationUpdates(mLocationCallback);
//
//	}
	public void onLocationUpdate(Location location) {
		if(location != null)
		{
			// Save device location
			DeviceLocation deviceLocation = new DeviceLocation(location.getTime(),location.getLatitude(),location.getLongitude());
			System.out.println("Location Update = "+deviceLocation);
			CryptoModule cryptoModule = CryptoModule.getInstance(getApplicationContext());
			EphId ephId = cryptoModule.getCurrentEphId();
			database.saveDeviceLocation(deviceLocation);
			BtLocToken btLocToken = new BtLocToken(ephId, deviceLocation);
			System.out.println("Saving bt+loc tokens");
			database.saveBroadcastBtLocHashes(btLocToken);
//			System.out.println("EphId = "+ toBase64(ephId.getData()));
//			System.out.println("Rounded timestamp = "+ deviceLocation.getRoundedTimestamp());
//			System.out.println("Geohashes = "+deviceLocation.getLocationHashes());
//
		}
		else{
			// TODO handle when Location is null
		}

	}
	protected void stopLocationUpdates(){
		LocationService.getInstance(this).stopLocationUpdates();
	}

	private void restartClient() {
		//also restart server here to generate a new mac-address so we get rediscovered by apple devices
		startServer();

		BluetoothState bluetoothState = startClient();
		if (bluetoothState == BluetoothState.NOT_SUPPORTED) {
			Logger.e(TAG, "bluetooth not supported");
			return;
		}

		handler.postDelayed(() -> {
			stopScanning();
			scheduleNextClientRestart(this, scanInterval);
		}, scanDuration);
	}

	private void restartServer() {
		BluetoothState bluetoothState = startServer();
		if (bluetoothState == BluetoothState.NOT_SUPPORTED) {
			Logger.e(TAG, "bluetooth not supported");
			return;
		}

		scheduleNextServerRestart(this);
	}

	public static void scheduleNextClientRestart(Context context, long scanInterval) {
		long now = System.currentTimeMillis();
		long delay = scanInterval - (now % scanInterval);
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent intent = new Intent(context, TracingServiceBroadcastReceiver.class);
		intent.setAction(ACTION_RESTART_CLIENT);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + delay, pendingIntent);
	}

	public static void scheduleNextServerRestart(Context context) {
		long nextEpochStart = CryptoModule.getInstance(context).getCurrentEpochStart() + CryptoModule.MILLISECONDS_PER_EPOCH;
		long nextAdvertiseChange = nextEpochStart;
		String calibrationTestDeviceName = AppConfigManager.getInstance(context).getCalibrationTestDeviceName();
		if (calibrationTestDeviceName != null) {
			long now = System.currentTimeMillis();
			nextAdvertiseChange = now - (now % (60 * 1000)) + 60 * 1000;
		}
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent intent = new Intent(context, TracingServiceBroadcastReceiver.class);
		intent.setAction(ACTION_RESTART_SERVER);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAdvertiseChange, pendingIntent);
	}

	private void stopForegroundService() {
		isFinishing = true;
		stopLocationUpdates();
		stopClient();
		stopServer();
		stopTrackingLocation();
		BluetoothServiceStatus.resetInstance();
		stopForeground(true);
		wl.release();
		stopSelf();
	}

	private BluetoothState startServer() {
		stopServer();
		if (startAdvertising) {
			bleServer = new BleServer(this);

			Logger.d(TAG, "startAdvertising");
			BluetoothState advertiserState = bleServer.startAdvertising();
			return advertiserState;
		}
		return null;
	}

	private void stopServer() {
		if (bleServer != null) {
			bleServer.stop();
			bleServer = null;
		}
	}

	private BluetoothState startClient() {
		stopClient();
		if (startReceiving) {
			bleClient = new BleClient(this);
			BluetoothState clientState = bleClient.start();
			return clientState;
		}
		return null;
	}

	private void stopScanning() {
		if (bleClient != null) {
			bleClient.stopScan();
		}
	}

	private void stopClient() {
		if (bleClient != null) {
			bleClient.stop();
			bleClient = null;
		}
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onDestroy() {
		Logger.i(TAG, "onDestroy()");

		unregisterReceiver(errorsUpdateReceiver);
		unregisterReceiver(bluetoothStateChangeReceiver);
		unregisterReceiver(locationServiceStateChangeReceiver);

		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
	}

}
