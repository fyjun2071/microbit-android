package com.samsung.microbit.service;

import android.app.Activity;
import android.app.IntentService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.text.style.UpdateLayout;
import android.util.Log;
import android.util.TimingLogger;

import com.samsung.microbit.MBApp;
import com.samsung.microbit.core.bluetooth.BluetoothUtils;
import com.samsung.microbit.data.constants.Constants;
import com.samsung.microbit.data.constants.UUIDs;
import com.samsung.microbit.data.model.ConnectedDevice;
import com.samsung.microbit.ui.activity.NotificationActivity;
import com.samsung.microbit.ui.activity.PopUpActivity;
import com.samsung.microbit.utils.FileUtils;
import com.samsung.microbit.utils.HexUtils;

import java.io.IOException;
import java.util.Timer;
import java.util.UUID;

import static com.samsung.microbit.data.constants.CharacteristicUUIDs.MEMORY_MAP;
import static com.samsung.microbit.data.constants.CharacteristicUUIDs.PARTIAL_FLASH_CONTROL;
import static com.samsung.microbit.data.constants.CharacteristicUUIDs.PARTIAL_FLASH_WRITE;
import static com.samsung.microbit.data.constants.GattServiceUUIDs.PARTIAL_FLASHING_SERVICE;
import static com.samsung.microbit.ui.PopUp.TYPE_ALERT;
import static com.samsung.microbit.ui.PopUp.TYPE_PROGRESS_NOT_CANCELABLE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_ACTION_CANCEL_PRESSED;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_ACTION_CLOSE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_ACTION_OK_PRESSED;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_ACTION_UPDATE_LAYOUT;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_ACTION_UPDATE_PROGRESS;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_CANCELABLE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_ICON;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_MESSAGE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_PROGRESS;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_TITLE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_EXTRA_TYPE;
import static com.samsung.microbit.ui.activity.PopUpActivity.INTENT_GIFF_ANIMATION_CODE;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 */

// A service that interacts with the BLE device via the Android BLE API.
public class PartialFlashService extends IntentService {

    private final static String TAG = PartialFlashService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    BluetoothGattService Service;

    BluetoothGattCharacteristic flashCharac;
    BluetoothGattCharacteristic flashControlCharac;

    private Boolean waitToSend = false;

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private byte packetState = PACKET_STATE_WAITING;

    private Boolean bleReady = false;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // DAL Hash
    String dalHash;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static String EXTRA_PF_FILE_PATH = "com.samsung.microbit.service.extra.EXTRA_FILE_PATH.";
    public final static String EXTRA_DEVICE_ADDRESS = "com.samsung.microbit.service.extra.EXTRA_DEVICE_ADDRESS.";
    public final static String EXTRA_DEVICE_NAME = "com.samsung.microbit.service.extra.EXTRA_DEVICE_NAME";
    public final static String EXTRA_DEVICE_PAIR_CODE = "com.samsung.microbit.service.extra.EXTRA_DEVICE_PAIR_CODE";
    public final static String EXTRA_FILE_MIME_TYPE = "com.samsung.microbit.service.extra.EXTRA_FILE_MIME_TYPE";
    public final static String EXTRA_KEEP_BOND = "com.samsung.microbit.service.extra.EXTRA_KEEP_BOND";
    public final static String INTENT_REQUESTED_PHASE = "com.samsung.microbit.service.extra.INTENT_REQUESTED_";
    public final static String EXTRA_WAIT_FOR_INIT_DEVICE_FIRMWARE = "com.samsung.microbit.service.extra.EXTRA_WAIT_FOR_INIT_DEVICE_FIRMWARE";

    LocalBroadcastManager localBroadcast;

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                    int newState) {
                    String intentAction;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        intentAction = ACTION_GATT_CONNECTED;
                        mConnectionState = STATE_CONNECTED;
                        Log.i(TAG, "Connected to GATT server.");
                        Log.i(TAG, "Attempting to start service discovery:" +
                                mBluetoothGatt.discoverServices());

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        intentAction = ACTION_GATT_DISCONNECTED;
                        mConnectionState = STATE_DISCONNECTED;
                        Log.i(TAG, "Disconnected from GATT server.");
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "onServicesDiscovered SUCCESS");
                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                    }

                    Service = mBluetoothGatt.getService(PARTIAL_FLASHING_SERVICE);
                    if (Service == null) {
                        Log.e(TAG, "service not found!");
                    }
                    bleReady = true;

                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                        bleReady = true;
                    }

                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic,
                                                int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        // Success
                        Log.v(TAG, "GATT status: Success");
                        bleReady = true;
                        waitToSend = false;
                    } else {
                        // Attempt to resend
                        Log.v(TAG, "GATT status:" + Integer.toString(status));
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    super.onCharacteristicChanged(gatt, characteristic);
                    byte notificationValue[] = characteristic.getValue();
                    Log.v(TAG, "Received Notification: " + bytesToHex(notificationValue));

                    packetState = notificationValue[0];

                }

                @Override
                public void onDescriptorWrite (BluetoothGatt gatt,
                                        BluetoothGattDescriptor descriptor,
                                        int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        Log.v(TAG, "Descriptor success");
                        bleReady = true;
                    }
                    Log.v(TAG, "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                }

            };

    public PartialFlashService() {
        super(TAG);

        IntentFilter popUpButtons = new IntentFilter();
        popUpButtons.addAction(INTENT_ACTION_OK_PRESSED);
        popUpButtons.addAction(INTENT_ACTION_CANCEL_PRESSED);

        localBroadcast = LocalBroadcastManager.getInstance(this);
        localBroadcast.registerReceiver(broadcastReceiver, popUpButtons);

    }

    // Write to BLE Flash Characteristic
    public Boolean writePartialFlash(byte data[]){

        flashCharac.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        flashCharac.setValue(data);
        boolean status = mBluetoothGatt.writeCharacteristic(flashCharac);
        return status;

    }


    public Boolean attemptPartialFlash(String filePath) {

        // Display progress
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.ACTION_USER_FOREGROUND);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(INTENT_EXTRA_TITLE, "Flashing");
        intent.putExtra(INTENT_EXTRA_MESSAGE, "Updating the firmware on your micro:bit");
        intent.putExtra(INTENT_GIFF_ANIMATION_CODE, 1);
        intent.putExtra(INTENT_EXTRA_TYPE, TYPE_PROGRESS_NOT_CANCELABLE);
        ComponentName cn = new ComponentName(this, PopUpActivity.class);
        intent.setComponent(cn);
        startActivity(intent);

        Log.v(TAG, filePath);
        long startTime = SystemClock.elapsedRealtime();

        int count = 0;
        int progressBar = 0;
        int numOfLines = 0;
        HexUtils hex;
        try {
            hex = new HexUtils();
            if (hex.findHexMetaData(filePath)) {

                if(!hex.getTemplateHash().equals(dalHash)){
                    return false;
                }

                numOfLines = hex.numOfLines(filePath);
                numOfLines = numOfLines - hex.getMagicLines();
                Log.v(TAG, "Total lines: " + numOfLines);

                // Ready to flash!
                // Set up BLE notification
                if(!mBluetoothGatt.setCharacteristicNotification(flashControlCharac, true)){
                    Log.e(TAG, "Failed to set up notifications");
                } else {
                    Log.v(TAG, "Notifications enabled");
                }

                BluetoothGattDescriptor descriptor = flashControlCharac.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

                if(bleReady) {
                    bleReady = false;
                    mBluetoothGatt.writeDescriptor(descriptor);
                }
                while(!bleReady);

                // Loop through data
                String hexData;
                int packetNum = 1;
                while(true){
                    // Get next data to write
                    hexData = hex.getNextData();
                    // Check if EOF
                    if(hex.getRecordType() != 0) break;

                    // Log record being written
                    Log.v(TAG, "Hex Data  : " + hexData);
                    Log.v(TAG, "Hex Offset: " + Integer.toHexString(hex.getRecordOffset()));

                    /* Write embedded source
                    // If Hex Data is Embedded Source Magic
                    if(hexData.length() == 32) {
                        if (hexData.substring(0, 15).equals("41140E2FB82FA2B"))
                        {
                            // Start of embedded source
                            Log.v(TAG, "Reached embedded source");
                            // Time execution
                            long endTime = SystemClock.elapsedRealtime();
                            long elapsedMilliSeconds = endTime - startTime;
                            double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                            Log.v(TAG, "Flash Time (No Embedded Source): " + Float.toString((float) elapsedSeconds) + " seconds");
                            break;
                        }
                    }
                    */

                    // Split into bytes
                    byte chunk[] = recordToByteArray(hexData, hex.getRecordOffset(), packetNum);

                    // Write without response
                    // Wait for previous write to complete
                    boolean writeStatus = writePartialFlash(chunk);
                    Log.v(TAG, "Hex Write: " + Boolean.toString(writeStatus));
                    if(!writeStatus) {
                        Log.e(TAG, "RETRANSMITTING");

                        // Send packet to sync MB
                        byte retransmitPacket[] = recordToByteArray("AAAAAAAAAAAAAAAA", 0x1234, packetNum);
                        writeStatus = writePartialFlash(retransmitPacket);

                        // If success continue, else throw error
                        if(writeStatus)
                        {
                            packetState = PACKET_STATE_RETRANSMIT;
                            count = 0;
                        } else {
                            // Throw error
                            // Error Message
                            Intent flashError = new Intent();
                            flashError.setAction(INTENT_ACTION_UPDATE_LAYOUT);
                            flashError.addCategory(Intent.ACTION_USER_FOREGROUND);
                            flashError.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            flashError.putExtra(INTENT_EXTRA_TITLE, "Flash Error");
                            flashError.putExtra(INTENT_EXTRA_MESSAGE, "Try again or flash via USB");
                            flashError.putExtra(INTENT_GIFF_ANIMATION_CODE, 2);
                            flashError.putExtra(INTENT_EXTRA_TYPE, TYPE_ALERT);
                            localBroadcast.sendBroadcast(flashError);
                        }

                    }

                    // Sleep after 4 packets
                    count++;
                    if(count == 4){
                        count = 0;
                        // Wait for notification
                        int waitTime = 0;
                        while(packetState == PACKET_STATE_WAITING);

                        // Reset to waiting state
                        packetState = PACKET_STATE_WAITING;

                    } else {
                        Thread.sleep(5);
                    }

                    // If notification is retransmit -> retransmit last block.
                    // Else set start of new block
                    if(packetState == PACKET_STATE_RETRANSMIT) {
                        hex.rewind();
                    } else {
                        hex.setMark();

                        // Update UI
                        Intent progressUpdate = new Intent();
                        progressUpdate.setAction(INTENT_ACTION_UPDATE_PROGRESS);
                        int currentProgress = (int)Math.round((100.0 * ((float)progressBar++ / (float)numOfLines)));
                        Log.v(TAG, "Current progress: " + currentProgress);
                        progressUpdate.putExtra(INTENT_EXTRA_PROGRESS, currentProgress);
                        localBroadcast.sendBroadcast( progressUpdate );
                    }

                    // Increment packet #
                    packetNum = packetNum + 1;

                }

                // Write End of Flash packet
                writePartialFlash(recordToByteArray("FFFFFFFFFFFFFFFF", 0xFFFF, 0xFFFF));

                // Finished Writing
                Log.v(TAG, "Flash Complete");

                // Time execution
                long endTime = SystemClock.elapsedRealtime();
                long elapsedMilliSeconds = endTime - startTime;
                double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                Log.v(TAG, "Flash Time: " + Float.toString((float)elapsedSeconds) + " seconds");

                // Success Message
                Intent flashSuccess = new Intent();
                flashSuccess.setAction(INTENT_ACTION_UPDATE_LAYOUT);
                flashSuccess.putExtra(INTENT_EXTRA_TITLE, "Flash Complete");
                flashSuccess.putExtra(INTENT_EXTRA_MESSAGE, "Restart your micro:bit");
                flashSuccess.putExtra(INTENT_GIFF_ANIMATION_CODE, 1);
                flashSuccess.putExtra(INTENT_EXTRA_TYPE, TYPE_ALERT);
                localBroadcast.sendBroadcast( flashSuccess );

            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return true;
    }

    /*
    Record to byte Array
    @param hexString string to convert
    @return byteArray of hex
     */
    private static byte[] recordToByteArray(String hexString, int offset, int packetNum){
        int len = hexString.length();
        byte[] data = new byte[(len/2) + 4];
        for(int i=0; i < len; i+=2){
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character.digit(hexString.charAt(i+1), 16));
        }

        data[(len/2)]   = (byte)(offset >> 8);
        data[(len/2)+1] = (byte)(offset & 0xFF);
        data[(len/2)+2] = (byte)(packetNum >> 8);
        data[(len/2)+3] = (byte)(packetNum & 0xFF);

        return data;
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize(String deviceId) {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            Log.e(TAG, "Unable to initialize BluetoothManager.");
            return false;
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        if (!mBluetoothAdapter.isEnabled())
            return false;

        Log.v(TAG,"Connecting to the device...");
        if (device == null) {
            device = mBluetoothAdapter.getRemoteDevice(deviceId);
        }

        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);

        //check mBluetoothGatt is available
        if (mBluetoothGatt == null) {
            Log.e(TAG, "lost connection");
            return false;
        }

        return true;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        final String filePath = intent.getStringExtra(EXTRA_PF_FILE_PATH);
        final String deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        final long notAValidFlashHexFile = intent.getLongExtra(EXTRA_WAIT_FOR_INIT_DEVICE_FIRMWARE, 0);

        initialize(deviceAddress);
        readMemoryMap();
        // If fails attempt full flash
        if(!attemptPartialFlash(filePath))
        {
            Log.v(TAG, "Start Full Flash");

            ConnectedDevice currentMicrobit = BluetoothUtils.getPairedMicrobit(this);

            MBApp application = MBApp.getApp();

            // final Intent service = new Intent(application, DfuService.class);
            final Intent service = new Intent(application, DfuService.class);
            service.putExtra(DfuService.EXTRA_DEVICE_ADDRESS, currentMicrobit.mAddress);
            service.putExtra(DfuService.EXTRA_DEVICE_NAME, currentMicrobit.mPattern);
            service.putExtra(DfuService.EXTRA_DEVICE_PAIR_CODE, currentMicrobit.mPairingCode);
            service.putExtra(DfuService.EXTRA_FILE_MIME_TYPE, DfuService.MIME_TYPE_OCTET_STREAM);
            service.putExtra(DfuService.EXTRA_FILE_PATH, filePath); // a path or URI must be provided.
            service.putExtra(DfuService.EXTRA_KEEP_BOND, false);
            service.putExtra(DfuService.INTENT_REQUESTED_PHASE, 2);
            if(notAValidFlashHexFile == Constants.JUST_PAIRED_DELAY_ON_CONNECTION) {
                service.putExtra(DfuService.EXTRA_WAIT_FOR_INIT_DEVICE_FIRMWARE, Constants.JUST_PAIRED_DELAY_ON_CONNECTION);
            }

            application.startService(service);
        }


    }

    /*
    Read Memory Map from the MB
     */
    private Boolean readMemoryMap() {
        boolean status; // Gatt Status


        try {
            while(!bleReady);
            // Get Characteristic
            BluetoothGattCharacteristic memoryMapChar = Service.getCharacteristic(MEMORY_MAP);

            if (memoryMapChar == null) {
                Log.e(TAG, "char not found!");
                return false;
            }

            flashCharac = Service.getCharacteristic(PARTIAL_FLASH_WRITE);
            if (flashCharac == null) {
                Log.e(TAG, "char not found!");
            }

            flashControlCharac = Service.getCharacteristic(PARTIAL_FLASH_CONTROL);
            if (flashControlCharac == null) {
                Log.e(TAG, "char not found!");
            } else {
                Log.v(TAG, "flash control: " + flashControlCharac.toString());
            }

            // Request Regions
            memoryMapChar.setValue(0xFF, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
            bleReady = false;
            status = mBluetoothGatt.writeCharacteristic(memoryMapChar);
            while(!bleReady);
            Log.v(TAG, "Region WRITE result: " + status);

            // Log Names
            bleReady = false;
            status = mBluetoothGatt.readCharacteristic(memoryMapChar);
            while(!bleReady);

            Log.v(TAG, "Region READ result: " + status);
            String regions = memoryMapChar.getStringValue(0);
            Log.v(TAG, "Regions: " + regions);

            // Iterate through regions to get ID #s
            int i = 0;
            byte[] byteArray;
            while(regions.charAt(3 * i) != ' '){
                Log.v(TAG, regions.substring(3 * i, (3 * i) + 3));

                // Get Start, End, and Hash of each Region
                // Request Region
                byte[] selector = {(byte)(i & 0xFF)};
                memoryMapChar.setValue(selector);
                bleReady = false;
                status = mBluetoothGatt.writeCharacteristic(memoryMapChar);
                while(!bleReady);
                Log.v(TAG, "Selector WRITE: " + status + ", DATA: " + memoryMapChar.getValue()[0]);

                // Log Start/End
                bleReady = false;
                status = mBluetoothGatt.readCharacteristic(memoryMapChar);
                while(!bleReady);
                byteArray = memoryMapChar.getValue();
                int startAddress = ((0xFF & byteArray[3]) << 24 | (0xFF & byteArray[2]) << 16 | (0xFF & byteArray[1]) << 8 | (0xFF & byteArray[0]));
                int endAddress   = ((0xFF & byteArray[11]) << 24 | (0xFF & byteArray[10]) << 16 | (0xFF & byteArray[9]) << 8 | (0xFF & byteArray[8]));
                Log.v(TAG, "Start: 0x" + Integer.toHexString(startAddress) + ", End: 0x" + Integer.toHexString(endAddress));

                // Log Hash
                bleReady = false;
                status = mBluetoothGatt.readCharacteristic(memoryMapChar);
                while(!bleReady);
                byteArray = memoryMapChar.getValue();
                // Get Hash From Byte Array
                // Convert to string
                Log.v(TAG, "Hash: " + bytesToHex(byteArray).substring(0,16));

                // IF DAL Store Hash for comparison
                if(regions.substring(3 * i, (3 * i) + 3).equals("DAL")){
                    dalHash = bytesToHex(byteArray).substring(0,16);
                }

                i++;
            }


        } catch (Exception e){
            Log.e(TAG, e.toString());
        }

        return true;
    }

    public static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /* Receive updates on user interaction with PopUp */
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, final Intent intent) {
            Log.v(TAG, "Received Broadcast: " + intent.toString());
            if(intent.getAction().equals(INTENT_ACTION_OK_PRESSED) || intent.getAction().equals(INTENT_ACTION_CANCEL_PRESSED))
            {
                // Close Pop Up
                Intent closePopUp = new Intent();
                closePopUp.setAction(INTENT_ACTION_CLOSE);
                localBroadcast.sendBroadcast( closePopUp );
            }
        }
    };
}
