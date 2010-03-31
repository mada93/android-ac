/*
 *  "Analog Compass" is an application for devices based on android os. 
 *  The application shows the orientation based on the intern magnetic sensor.   
 *  Copyright (C) 2009  Dieter Roth
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU General Public License as published by the Free Software Foundation; either version 3 of 
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU General Public License for more details.

 *  You should have received a copy of the GNU General Public License along with this program; 
 *  if not, see <http://www.gnu.org/licenses/>.
 */
 
package de.rothbayern.android.ac;

import android.app.*;
import android.content.*;
import android.graphics.Canvas;
import android.hardware.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import de.rothbayern.android.ac.misc.Util;
import de.rothbayern.android.ac.pref.CompassPreferences;


/**
 * @author Dieter Roth
 *
 * Main activity which holds the compass.
 */
public class ACActivity extends Activity {

	// Controls
	private IAnimCompass compassView;
	private AnimThread animThread;
	private SensorManager mSensorManager;

	
	// Possible speeds for needle movement
	public static final int SPEED_SLOW = 0;
	public static final int SPEED_NORMAL = 1;
	public static final int SPEED_FAST = 2;
	public static final int SPEED_DIRECT = 3;
	public static final int SPEED_SWING = 4;
	private int speedMode = SPEED_NORMAL;

	// offset for calibration
	private float offset = 0.0f;

	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		System.exit(0);		// Close if there are any resource left. 
	}

	@Override
	public void onCreate(Bundle savedState) {
		super.onCreate(savedState);
//		Log.d("cycle","onCreate"+this);
		// Do first to init preferences. This is a singleton.
		CompassPreferences prefs = CompassPreferences.getPreferences(this);
		prefs.checkVersion();

		
		setContentView(R.layout.main);
		compassView = (IAnimCompass) findViewById(R.id.viewWorld);

		// Prepare sensor to deliver data. Show message if there is no orientation sensor
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		Sensor testSensorOrientation = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
		if (testSensorOrientation == null) {
			fireShowNoHardwareCompass();
		} else {
			boolean isPresent = mSensorManager
					.registerListener(mListener, testSensorOrientation, SensorManager.SENSOR_DELAY_NORMAL);
			if (!isPresent) {
				fireShowNoHardwareCompass();
			}
			mSensorManager.unregisterListener(mListener);

		}
		
		// load preferences
		takePreferences();
		Toast.makeText(this, R.string.hint_tap_settings, Toast.LENGTH_LONG).show();
		


	}

	// Show messages boxes at start time
	// messages are delayed, showed only once and loosely bound to the activity 
	private static final int MSG_SHOW_NO_HARDWARE_COMPASS = 100;
	public static final int MSG_SHOW_CHANGE_VERSION = 101;
	public Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_SHOW_NO_HARDWARE_COMPASS: {
					showWarnNoHardwareCompass();
					break;
				}
				case MSG_SHOW_CHANGE_VERSION: {
					String params[] = (String[]) msg.obj;
					showChangeVersion(params[0], params[1]);
					break;
				}
			}
		}
	};
	
	// change version is showed only once
	private boolean shownChangeVersion = false;
	private void showChangeVersion(String oldVersion, String newVersion) {
		if (!shownChangeVersion) {
			shownChangeVersion = true;
			LayoutInflater factory = LayoutInflater.from(this);
			View greetingView = factory.inflate(R.layout.greeting, null);
			TextView rateView = (TextView) greetingView.findViewById(R.id.greeting_ask_for_rate);
			Util.addLink(this, rateView, R.string.greet_ask_for_rate_link_marker, R.string.market);
			TextView hintView = (TextView) greetingView.findViewById(R.id.greeting_hint_internal_compass);
			Util.addLink(this, hintView, R.string.greet_hint_internal_compass_link_marker,
					R.string.greet_hint_internal_compass_link_url);
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(R.string.app_name);
			builder.setIcon(R.drawable.compass_icon);
			builder.setNeutralButton(R.string.close, null);
			builder.setView(greetingView);
			AlertDialog alert = builder.create();
			alert.show();
		}
	}

	private boolean shownWarnNoHardwareCompass = false;
	private void showWarnNoHardwareCompass() {
		if (!shownWarnNoHardwareCompass) {
			shownWarnNoHardwareCompass = true;
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(android.R.string.dialog_alert_title);
			builder.setIcon(android.R.drawable.ic_dialog_alert);
			builder.setNeutralButton(R.string.close, null);
			String msg = Util.loadStringFromRawResource(getResources(), R.raw.no_orientation_sensor_warning);
			Spanned sMsg = Html.fromHtml(msg);
			builder.setMessage(sMsg);
			AlertDialog dialog = builder.create();
			dialog.show();
		}
	}

	private void fireShowNoHardwareCompass() {
		Message m = new Message();
		m.what = MSG_SHOW_NO_HARDWARE_COMPASS;
		myHandler.sendMessageDelayed(m, 2200);
	}

	
	

	/**
	 * say if needle needs repainting
	 * 
	 * @param pArrived				needle was near setpoint 
	 * @param pSpeed				current speed of needle
	 * @param curNeedleDirection	current direction of the needle
	 * @param setPoint				direction to where the needle will point (sometime in the future)
	 * @return true if needle should be repainted
	 */
	private static boolean calcNeedPainting(boolean pArrived, float pSpeed, float curNeedleDirection, float setPoint) {
		if (pArrived) {
			if (Math.abs(curNeedleDirection - setPoint) > AnimThread.LEAVED_EPS) {
				// if (Config.LOGD) {
				// Log.d("arrived", "leave: " + pArrived + ", " + pSpeed + ", "
				// + pLastDirection + ", " + pCurSetPoint);
				// }
				return (true);
			}
			return (false);
		} else {
			if (Math.abs(curNeedleDirection - setPoint) < AnimThread.ARRIVED_EPS && Math.abs(pSpeed) < AnimThread.SPEED_EPS) {
				// if (Config.LOGD) {
				// Log.d("arrived", "arrived: " + pArrived + ", " + pSpeed +
				// ", " + pLastDirection + ", " + pCurSetPoint);
				// }
				return (false);
			}
			return (true);
		}
	}

	/**
	 * Adjusts the needle smoothly to the setpoint.
	 * @author Dieter Roth 
	 */
	class AnimThread extends Thread {

		private static final float ARRIVED_EPS = 0.7f; // tolerance to become arrived
		private static final float LEAVED_EPS = 2.5f; // tolerance to leave arrived
		private static final float SPEED_EPS = 0.5f; // tolerance of speed

		private static final int LONG_SPAN_MILLIS = 100;	// time to sleep if the needle is in state arrived
		private static final int STD_SPAN_MILLIS = 20;		// time to sleep if the neelde have to move to the setpoint.
															// about 50 FPS

		private boolean running = true; 	// Thread has to do his work
		private boolean finished = false; 	// Thread has finished his work
		private float setPoint = 0.0f;		// direction the needle should point (sometime in the future)

		public AnimThread() {
		}

		public void setSetPoint(float setPoint) {
			this.setPoint = setPoint;
		}

		public boolean isFinished() {
			return finished;
		}

		public void setRunning(boolean running) {
			this.running = running;
		}

		@Override
		public void run() {
			finished = false;
			float speed = 0;
			float lastDirection = 0;
			boolean forcePaint = true;
			boolean arrived = false; // The needle has not arrived the setpoint

			// repaint all the time
			while (running) {
				// copy to local variable for one iteration
				float curSetPoint = setPoint;
				boolean needPainting = calcNeedPainting(arrived, speed, lastDirection, curSetPoint) || forcePaint;
				// Something to do?
				if (needPainting) {
					arrived = false;
					
					// !! important calculation, which do a smooth movement
					// diff to setpoint normalized [-180, 180]
					// to control clockwise or counterclockwise aproximation
					float diff = Util.calcNormDiff(lastDirection, curSetPoint);
					speed = calcSpeed(diff, speed, speedMode);
					curSetPoint = lastDirection + speed;
					lastDirection = curSetPoint;
					// !!

					// set the direction where the needle points in the next step
					// an paint the needle
					compassView.setDirection(curSetPoint);
					forcePaint = compassView.doAnim(forcePaint);

				} else {
					arrived = true;
				}

				// Wait for next drawing
				try {
					if (arrived) {
						// Save battery if there will be nothing to do.
						preferSensorListenerState(ACActivity.SENSOR_LISTENER_STATE_SLEEP);
						Thread.sleep(LONG_SPAN_MILLIS);
					} 
					else {
						preferSensorListenerState(ACActivity.SENSOR_LISTENER_STATE_ACTION);
						Thread.sleep(STD_SPAN_MILLIS);

					}
				} catch (InterruptedException e) {
				}
			} // while
			finished = true; // indicator => thread has finished
		}


		/**
		 * calculates the new speed the needle moves to the setpoint
		 * @param diff		distance to the setpoint
		 * @param oldSpeed		
		 * @param speedMode	determines which physics to use
		 * @return	the new speed
		 */
		private float calcSpeed(float diff, float oldSpeed, int speedMode) {
			switch (speedMode) {
				case SPEED_DIRECT: {
					oldSpeed = oldSpeed * 0f; // friction
					oldSpeed += diff; // acceleration
					return oldSpeed;
				}
				case SPEED_FAST: {
					oldSpeed = oldSpeed * 0.75f; // friction
					oldSpeed += diff / 8.0f; // acceleration
					return oldSpeed;
				}
				case SPEED_SLOW: {
					oldSpeed = oldSpeed * 0.75f; // friction
					oldSpeed += diff / 40.0f; // acceleration
					return oldSpeed;
				}

				case SPEED_SWING: {
					oldSpeed = oldSpeed * 0.97f; // friction
					oldSpeed += diff / 10.0f; // acceleration
					return oldSpeed;
				}

				case SPEED_NORMAL:
				default: {
					oldSpeed = oldSpeed * 0.75f; // friction
					oldSpeed += diff / 20.0f; // acceleration
					return oldSpeed;
				}
			}
		}

	}



	// Handling of sensormanager and sensor values
	// sensor can be delivered with a high rate (sucks battery)
	// or can deliver with a low rate (saving battery)
	// here is some intelligence for switching these modes
	private static final int SENSOR_LISTENER_STATE_OFF = 0;
	protected static final int SENSOR_LISTENER_STATE_SLEEP = 1;
	protected static final int SENSOR_LISTENER_STATE_ACTION = 2;
	private int sensorListenerState = SENSOR_LISTENER_STATE_OFF;
	
	private void setSensorListenerState(int newState) {
		if (newState == sensorListenerState) // nothing to do if settings are already similar
			return;
		// if (Config.LOGD) {
		// Log.d("sensor register", "SENSOR_LISTENER_STATE_OFF");
		// }
		
		// stop old mode
		mSensorManager.unregisterListener(mListener);
		sensorListenerState = SENSOR_LISTENER_STATE_OFF;

		// set the desired new mode
		if (newState == SENSOR_LISTENER_STATE_SLEEP) {
			// if (Config.LOGD) {
			// Log.d("sensor register", "SENSOR_LISTENER_STATE_NORMAL");
			// }
			mSensorManager.registerListener(mListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION),
					SensorManager.SENSOR_DELAY_NORMAL);
			sensorListenerState = newState;
		}
		if (newState == SENSOR_LISTENER_STATE_ACTION) {
			// if (Config.LOGD) {
			// Log.d("sensor register", "SENSOR_LISTENER_STATE_ACTION");
			// }
			mSensorManager.registerListener(mListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION),
					SensorManager.SENSOR_DELAY_UI);
			sensorListenerState = newState;
		}

	}

	protected void preferSensorListenerState(int newState) {
		if (sensorListenerState != SENSOR_LISTENER_STATE_OFF) {
			setSensorListenerState(newState);
		}
	}

	private void startAnim() {
		if (animThread == null) {
			Runtime.getRuntime().gc();
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
			animThread = new AnimThread();
			animThread.start();
		}
	}

	private void stopAnim() {
		if (animThread != null) {
			animThread.setRunning(false);
			try {
				animThread.join(AnimThread.LONG_SPAN_MILLIS * 2);
			} catch (InterruptedException e) {
			}
			animThread = null;
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		setSensorListenerState(SENSOR_LISTENER_STATE_ACTION);
		compassView.loadPrefs();
		startAnim();
	}

	@Override
	protected void onPause() {
		// Stop display
		setSensorListenerState(SENSOR_LISTENER_STATE_OFF);
		stopAnim();
		super.onPause();
	}

	private float avgDirection = 0;
	private final SensorEventListener mListener = new SensorEventListener() {

		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			// if(accuracy>5){
			// Log.w("accuracy low", Integer.toString(accuracy));
			// }
		}

		public void onSensorChanged(SensorEvent event) {
			float newDirection = event.values[0] + offset;
			float diff = newDirection - avgDirection;
			diff = Util.normAngle(diff);
			if (Math.abs(diff) < 5) {
				newDirection = avgDirection + diff / 4;
			} else {
				preferSensorListenerState(SENSOR_LISTENER_STATE_ACTION);

			}
			newDirection = Util.normAngle(newDirection);
			avgDirection = newDirection;
			if (animThread != null) {
				animThread.setSetPoint(avgDirection);
			}
		}
	};

	// Menu
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.options, menu);
		return true;
	}

	private static final int CALIBRATION_ACTIVITY_REQUEST = 100;
	private static final int PREFERENCES_ACTIVITY_REQUEST = 101;

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == CALIBRATION_ACTIVITY_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
			float offset = data.getFloatExtra(CalibrationActivity.RESULT_NAME_OFFSET, 0.0f);
			CompassPreferences prefs = CompassPreferences.getPreferences();
			prefs.setFloat(prefs.PREFS_COMPASS_OFFSET_KEY, offset);
			takePreferences();
		}
		if (requestCode == PREFERENCES_ACTIVITY_REQUEST) {
			takePreferences();
		}

	}

	private void takePreferences() {
		CompassPreferences prefs = CompassPreferences.getPreferences();

		int bgColor = prefs.getInt(prefs.PREFS_COMPASS_BACKGROUNDCOLOR_KEY);
		compassView.setBgColor(bgColor);

		int rose = prefs.getInt(prefs.PREFS_COMPASS_LAYOUT_KEY);
		compassView.setCompassLayout(rose);

		speedMode = prefs.getInt(prefs.PREFS_COMPASS_SPEED_KEY);

		offset = prefs.getFloat(prefs.PREFS_COMPASS_OFFSET_KEY);

	}

	/* Handles item selections */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.mnuCalibrate: {
				// Start Calibration
				stopAnim();
				Intent intent = new Intent(this, CalibrationActivity.class);
				CompassPreferences prefs = CompassPreferences.getPreferences();
				intent.putExtra("offset", prefs.getFloat(prefs.PREFS_COMPASS_OFFSET_KEY));
				startActivityForResult(intent, CALIBRATION_ACTIVITY_REQUEST);
				// Look at this.onActivityResult() for result.
				return true;
			}
			case R.id.mnuLayout: {
				stopAnim();
				Intent intent = new Intent(this, PreferencesActivity.class);
				startActivityForResult(intent, PREFERENCES_ACTIVITY_REQUEST);
				// startActivity(intent);
				return true;
			}
			case R.id.mnuInfo: {
				stopAnim();
				Intent intent = new Intent(this, InfoActivity.class);
				startActivity(intent);
				return true;
			}
		}
		return false;
	}
	
	

}