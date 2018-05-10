/**
 * Smart Light Remote
 *
 * @author Dan Bunnell
 *
 * This activity turns your Android device into a remote controller for a smart-light.
 */

package com.danbunnell.smartlightremote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.danbunnell.smartlightremote.BLE.RBLGattAttributes;
import com.danbunnell.smartlightremote.BLE.RBLService;
import com.danbunnell.smartlightremote.acceleration.AccelerometerDataProvider;
import com.danbunnell.smartlightremote.acceleration.AccelerometerListener;
import com.danbunnell.smartlightremote.common.MovingAverageFilter;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private String mTargetDeviceName = "xJ91s4k"; // Name must match device in 'smart-lite' project
    private int mNameLen = mTargetDeviceName.length() + 1;

    private final static String TAG = MainActivity.class.getSimpleName();

    private final static byte CMD_CLIENT_NOTIFY_HUE = 0x01;
    private final static byte CMD_CLIENT_NOTIFY_SATURATION = 0x02;

    private final static byte CMD_ENABLE_REMOTE_CONTROL = 0x01;
    private final static byte CMD_SET_HUE = 0x02;
    private final static byte CMD_SET_SATURATUION = 0x03;

    // Declare all variables associated with the UI components
    private Button mConnectBtn = null;
    private TextView mDeviceName = null;
    private TextView mRssiValue = null;
    private TextView mUUID = null;
    private TextView txtLightHueValue = null;
    private TextView txtLightSaturationValue = null;
    private ToggleButton btnRemoteControlEnabled = null;
    private ToggleButton btnUseAccelerometer = null;
    private SeekBar seekLightHue = null;
    private SeekBar seekLightSaturation = null;
    private String mBluetoothDeviceName = "";
    private String mBluetoothDeviceUUID = "";

    // Declare all Bluetooth stuff
    private BluetoothGattCharacteristic mCharacteristicTx = null;
    private RBLService mBluetoothLeService;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mDevice = null;
    private String mDeviceAddress;

    private boolean flag = true;
    private boolean mConnState = false;
    private boolean mScanFlag = false;

    private byte[] mData = new byte[3]; // Keep global to avoid reallocation of memory
    private static final int REQUEST_ENABLE_BT = 1;
    private static final long SCAN_PERIOD = 2000;   // millis

    final private static char[] hexArray = { '0', '1', '2', '3', '4', '5', '6',
            '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private int currentHue = 0;
    private int currentSaturation = 255;

    private AccelerometerDataProvider accelerometerProvider;

    /**
     * Manages the connection with our Android BLE service
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        /**
         * Called when service connected.
         *
         * @param componentName the component name
         * @param service       the service to bind to
         */
        @Override
        public void onServiceConnected(ComponentName componentName,
                                       IBinder service) {
            mBluetoothLeService = ((RBLService.LocalBinder) service)
                    .getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
        }

        /**
         * Called when service disconnected.
         *
         * @param componentName the component name
         */
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    /**
     * Called when service is disconnected
     */
    private void onServiceDisconnect() {
        flag = false;
        mConnState = false;

        btnRemoteControlEnabled.setEnabled(flag);
        btnUseAccelerometer.setEnabled(flag);
        seekLightHue.setEnabled(flag);
        seekLightSaturation.setEnabled(flag);
        mConnectBtn.setText("Connect");
        mRssiValue.setText("");
        mDeviceName.setText("");
        mUUID.setText("");
    }

    /**
     * Called when service connects.
     */
    private void onServiceConnect() {
        flag = true;
        mConnState = true;

        btnRemoteControlEnabled.setEnabled(flag);
        mConnectBtn.setText("Disconnect");
    }

    /**
     * Handles responding to the paired BLE device.
     */
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        /**
         * Called when data received from paired device.
         *
         * @param context context of paired device
         * @param intent  paired device intent
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (RBLService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Toast.makeText(getApplicationContext(), "Disconnected",
                        Toast.LENGTH_SHORT).show();
                onServiceDisconnect();
            } else if (RBLService.ACTION_GATT_SERVICES_DISCOVERED
                    .equals(action)) {
                Toast.makeText(getApplicationContext(), "Connected",
                        Toast.LENGTH_SHORT).show();

                getGattService(mBluetoothLeService.getSupportedGattService());
            } else if (RBLService.ACTION_DATA_AVAILABLE.equals(action)) {
                mData = intent.getByteArrayExtra(RBLService.EXTRA_DATA);
                byte command = mData[0];
                byte upper = mData[1];
                byte lower = mData[2];

                switch(command) {
                    case CMD_CLIENT_NOTIFY_HUE:
                        int hue = bytesToWord(upper, lower);
                        updateCurrentHue(hue);
                        break;
                    case CMD_CLIENT_NOTIFY_SATURATION:
                        int saturation = upper & 0xFF;
                        updateCurrentSaturation(saturation);
                    default:
                        Log.w(TAG, String.format("UNKNOWN CMD: %x", command));
                }
            } else if (RBLService.ACTION_GATT_RSSI.equals(action)) {
                displayConnectionInfo(intent.getStringExtra(RBLService.EXTRA_DATA));
            }
        }
    };

    /**
     * Displays Bluetooth connection information.
     *
     * @param data Data related to the Bluetooth connection
     */
    private void displayConnectionInfo(String data) {
        if (data != null) {
            mRssiValue.setText(data);
            mDeviceName.setText(mBluetoothDeviceName);
            mUUID.setText(mBluetoothDeviceUUID);
        }
    }

    /**
     * Displays the current hue sent from the smart light.
     *
     * @param hue current light hue
     */
    private void updateCurrentHue(int hue) {
        currentHue = hue;
        txtLightHueValue.setText(String.format("%d", currentHue));

        int currentColor = getColorFromHSB(currentHue, currentSaturation, 255);

        txtLightHueValue.setBackgroundColor(currentColor);
        seekLightHue.setProgress(currentHue);
    }

    /**
     * Records the change in saturation sent from client.
     *
     * @param saturation current light saturation
     */
    private void updateCurrentSaturation(int saturation) {
        currentSaturation = saturation;
        txtLightSaturationValue.setText(String.format("%d", saturation));

        int currentColor = getColorFromHSB(currentHue, currentSaturation, 255);

        txtLightHueValue.setBackgroundColor(currentColor);
        seekLightSaturation.setProgress(currentSaturation);
    }

    /**
     * Initialize based on BLE protocol provided.
     *
     * @param gattService the protocol provider
     */
    private void getGattService(BluetoothGattService gattService) {
        if (gattService == null)
            return;

        onServiceConnect();
        startReadRssi();

        mCharacteristicTx = gattService.getCharacteristic(RBLService.UUID_BLE_SHIELD_TX);

        BluetoothGattCharacteristic characteristicRx = gattService
                .getCharacteristic(RBLService.UUID_BLE_SHIELD_RX);
        mBluetoothLeService.setCharacteristicNotification(characteristicRx,true);
        mBluetoothLeService.readCharacteristic(characteristicRx);
    }

    /**
     * Read the BLE signal strength.
     */
    private void startReadRssi() {
        new Thread() {
            public void run() {

                while (flag) {
                    mBluetoothLeService.readRssi();
                    try {
                        sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            };
        }.start();
    }

    /**
     * Starts procedure to scan for BLE devices.
     */
    private void scanLeDevice() {
        new Thread() {

            @Override
            public void run() {
                mBluetoothAdapter.startLeScan(mLeScanCallback);

                try {
                    Thread.sleep(SCAN_PERIOD);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            }
        }.start();
    }

    /**
     * Handles data received from BLE scanning.
     */
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        /**
         * Called back with information from scan.
         *
         * @param device     the BLE device found
         * @param rssi       the connection signal strength
         * @param scanRecord the scanned device's identifying information
         */
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi,
                             final byte[] scanRecord) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    byte[] serviceUuidBytes = new byte[16];
                    String serviceUuid = "";
                    for (int i = (21+mNameLen), j = 0; i >= (6+mNameLen); i--, j++) {
                        serviceUuidBytes[j] = scanRecord[i];
                    }

                    serviceUuid = bytesToHex(serviceUuidBytes);
                    if (stringToUuidString(serviceUuid).equals(
                            RBLGattAttributes.BLE_SHIELD_SERVICE
                                    .toUpperCase(Locale.ENGLISH)) && device.getName().equals(mTargetDeviceName)) {
                        mDevice = device;
                        mBluetoothDeviceName = mDevice.getName();
                        mBluetoothDeviceUUID = serviceUuid;
                    }
                }
            });
        }
    };

    /**
     * Called on activity creation
     *
     * @param savedInstanceState the saved state of the activity
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.accelerometerProvider =  new AccelerometerDataProvider(
                (SensorManager) getSystemService(Context.SENSOR_SERVICE),
                5,
                new MovingAverageFilter(50));
        accelerometerProvider.registerCallback(
                TAG,
                new AccelerometerListener() {
                    @Override
                    public void onAccelerometerData(float magnitude, float filteredMagnitude) {
                        sendSetHueCommand((int) ((Math.min(30, filteredMagnitude) / 30) * 359));
                    }});
        // Associate all UI components with variables
        mConnectBtn = (Button) findViewById(R.id.connectBtn);
        mDeviceName = (TextView) findViewById(R.id.deviceName);
        mRssiValue = (TextView) findViewById(R.id.rssiValue);
        txtLightHueValue = (TextView) findViewById(R.id.txtLightHueValue);
        txtLightSaturationValue = (TextView) findViewById(R.id.txtLightSaturationValue);
        btnRemoteControlEnabled = (ToggleButton) findViewById(R.id.btnRemoteControlEnabled);
        btnUseAccelerometer = (ToggleButton) findViewById(R.id.btnUseAccelerometer);
        seekLightHue = (SeekBar) findViewById(R.id.seekLightHue);
        seekLightSaturation = (SeekBar) findViewById(R.id.seekLightSaturation);
        mUUID = (TextView) findViewById(R.id.uuidValue);

        // Connection button click event
        mConnectBtn.setOnClickListener(new View.OnClickListener() {

            /**
             * Called on Connect button click.
             *
             * @param v the current view
             */
            @Override
            public void onClick(View v) {
                if (mScanFlag == false) {
                    scanLeDevice();

                    Timer mTimer = new Timer();
                    mTimer.schedule(new TimerTask() {

                        @Override
                        public void run() {
                            if (mDevice != null) {
                                mDeviceAddress = mDevice.getAddress();
                                mBluetoothLeService.connect(mDeviceAddress);
                                mScanFlag = true;
                            } else {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Toast toast = Toast
                                                .makeText(
                                                        MainActivity.this,
                                                        "Could not find target device.",
                                                        Toast.LENGTH_SHORT);
                                        toast.setGravity(0, 0, Gravity.CENTER);
                                        toast.show();
                                    }
                                });
                            }
                        }
                    }, SCAN_PERIOD);
                }

                System.out.println(mConnState);
                if (mConnState == false) {
                    mBluetoothLeService.connect(mDeviceAddress);
                } else {
                    mBluetoothLeService.disconnect();
                    mBluetoothLeService.close();
                    onServiceDisconnect();
                }
            }
        });

        btnRemoteControlEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * Send remote control enabled status to client.
             *
             * @param buttonView a button view
             * @param isChecked  flag representing status of button
             */
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    seekLightHue.setEnabled(true);
                    seekLightSaturation.setEnabled(true);
                    btnUseAccelerometer.setEnabled(true);
                } else {
                    seekLightHue.setEnabled(false);
                    seekLightSaturation.setEnabled(false);
                    btnUseAccelerometer.setEnabled(false);
                    btnUseAccelerometer.setChecked(false);
                }

                byte buf[] = new byte[] {
                        CMD_ENABLE_REMOTE_CONTROL,
                        (byte) (isChecked ? 0x01 : 0x00),
                        (byte) 0x00 };

                mCharacteristicTx.setValue(buf);
                mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
            }
        });

        // Configure the Hue seek bar
        seekLightHue.setEnabled(false);
        seekLightHue.setMax(359);
        seekLightHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /**
             * Called when seek bar value changes.
             *
             * @param seekBar  the seek bar
             * @param progress the seek bar value
             * @param fromUser flag representing if value was changed by user
             */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    sendSetHueCommand(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar){}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Configure the Hue seek bar
        seekLightSaturation.setEnabled(false);
        seekLightSaturation.setMax(255);
        seekLightSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /**
             * Called when seek bar value changes.
             *
             * @param seekBar  the seek bar
             * @param progress the seek bar value
             * @param fromUser flag representing if value was changed by user
             */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    sendSetSaturationCommand(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar){}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnUseAccelerometer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * Send remote control enabled status to client.
             *
             * @param buttonView a button view
             * @param isChecked  flag representing status of button
             */
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    seekLightHue.setEnabled(false);
                    seekLightSaturation.setEnabled(false);
                    accelerometerProvider.onStart();
                } else {
                    if(btnRemoteControlEnabled.isChecked()) {
                        seekLightHue.setEnabled(true);
                        seekLightSaturation.setEnabled(true);
                    }

                    accelerometerProvider.onStop();
                }
            }
        });

        // Verify Bluetooth LE supported
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
                    .show();
            finish();
        }

        final BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        Intent gattServiceIntent = new Intent(MainActivity.this, RBLService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    /**
     * Called on activity resume.
     */
    @Override
    protected void onResume() {
        super.onResume();

        // If disabled, request to enable Bluetooth
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

    }

    /**
     * Called on activity stop.
     */
    @Override
    protected void onStop() {
        super.onStop();

        flag = false;

        unregisterReceiver(mGattUpdateReceiver);
    }

    /**
     * Called on activity destruction.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mServiceConnection != null)
            unbindService(mServiceConnection);
    }

    // Create a list of intent filters for Gatt updates. Created by the RedBear team.
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(RBLService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(RBLService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(RBLService.ACTION_GATT_RSSI);

        return intentFilter;
    }

    /**
     * Called when Bluetooth-enable request answered.
     *
     * @param requestCode the request identifier
     * @param resultCode  the result
     * @param data        additional data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Sends the set hue command to client.
     *
     * @param hue the hue to set
     */
    private void sendSetHueCommand(int hue) {
        byte[] hueBytes = wordToBytes(hue);

        byte[] buf = new byte[]{CMD_SET_HUE, hueBytes[0], hueBytes[1]};

        mCharacteristicTx.setValue(buf);
        mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
    }

    /**
     * Sends the set saturation command to client.
     *
     * @param saturation the saturation to set
     */
    private void sendSetSaturationCommand(int saturation) {
        byte[] buf = new byte[]{CMD_SET_SATURATUION, (byte) (saturation & 0xFF), 0x00};

        mCharacteristicTx.setValue(buf);
        mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
    }

    /**
     * Gets the RGB value from HSB.
     *
     * @param hue        the hue
     * @param sat        the saturation
     * @param brightness the brightness
     * @return           an RGB value
     */
    private static int getColorFromHSB(int hue, int sat, int brightness) {
        int r = 0;
        int g = 0;
        int  b = 0;
        int base;

        if (sat == 0) {
            r = brightness;
            g = brightness;
            b = brightness;
        } else  {
            base = (((255 - sat) * brightness) >> 8);

            switch(hue / 60) {
                case 0:
                    r = brightness;
                    g = ((((brightness - base) * hue) / 60) + base);
                    b = base;
                    break;

                case 1:
                    r = ((((brightness - base) * (60 - (hue % 60))) / 60) + base);
                    g = brightness;
                    b = base;
                    break;

                case 2:
                    r = base;
                    g = brightness;
                    b = ((((brightness - base) * (hue % 60)) / 60) + base);
                    break;

                case 3:
                    r = base;
                    g = ((((brightness - base) * (60 - (hue % 60))) / 60) + base);
                    b = brightness;
                    break;

                case 4:
                    r = ((((brightness - base) * (hue % 60)) / 60) + base);
                    g = base;
                    b = brightness;
                    break;

                case 5:
                    r = brightness;
                    g = base;
                    b = ((((brightness - base) * (60 - (hue % 60))) / 60) + base);
                    break;
            }
        }

        return (0xff) << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
    }

    /**
     * Converts an upper and lower byte into a single 16-bit word.
     *
     * @param upper upper byte
     * @param lower lower byte
     * @return      a 16-bit word
     */
    private static int bytesToWord(byte upper, byte lower) {
        return ((upper << 8) & 0x0000ff00) | (lower & 0x000000ff);
    }

    /**
     * Converts a 16-bit word into upper and lower bytes.
     * @param word a 16-bit word
     * @return     array of bytes, ordered upper then lower
     */
    private static byte[] wordToBytes(int word) {
        return new byte[] { (byte) ((word >> 8) & 0xFF), (byte) (word & 0xFF)};
    }

    /**
     * Converts an identifier string to a UUID-formatted string.
     * @param uuid identifier string
     * @return     UUID-formatted string
     */
    private static String stringToUuidString(String uuid) {
        StringBuffer newString = new StringBuffer();
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(0, 8));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(8, 12));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(12, 16));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(16, 20));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(20, 32));

        return newString.toString();
    }

    /**
     * Converts a byte array to hexadecimal.
     *
     * @param bytes a byte array
     * @return      a HEX string
     */
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}