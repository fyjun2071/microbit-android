package com.samsung.microbit.service;

import android.app.IntentService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.util.Log;

import com.samsung.microbit.utils.HexUtils;

import java.io.IOException;

import static com.samsung.microbit.data.constants.CharacteristicUUIDs.MEMORY_MAP;
import static com.samsung.microbit.data.constants.CharacteristicUUIDs.PARTIAL_FLASH_WRITE;
import static com.samsung.microbit.data.constants.GattServiceUUIDs.PARTIAL_FLASHING_SERVICE;

/**
 * A class to communicate with and flash the micro:bit while avoiding the DFU Service
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

    private Boolean waitToSend = false;

    private Boolean bleReady = false;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

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

            };

    public PartialFlashService() {
        super(TAG);
    }

    // Write to BLE Flash Characteristic
    public Boolean writePartialFlash(byte data[]){

        BluetoothGattCharacteristic charac = Service.getCharacteristic(PARTIAL_FLASH_WRITE);
        if (charac == null) {
            Log.e(TAG, "char not found!");
            return false;
        }

        charac.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        charac.setValue(data);
        boolean status = mBluetoothGatt.writeCharacteristic(charac);
        return status;

    }

    public Boolean attemptPartialFlash(String filePath) {

        Log.v(TAG, filePath);

        int count = 0;
        HexUtils hex;
        try {
            hex = new HexUtils();
            if (hex.findHexMetaData(filePath)) {
                // Ready to flash!

                // Loop through data
                String hexData;
                while(true){
                    // Get next data to write
                    hexData = hex.getNextData();
                    // Check if EOF
                    if(hex.getRecordType() != 0) break;

                    // Log record being written
                    Log.v(TAG, "Hex Data  : " + hexData);
                    Log.v(TAG, "Hex Offset: " + Integer.toHexString(hex.getRecordOffset()));

                    // Split into bytes
                    byte chunk[] = recordToByteArray(hexData, hex.getRecordOffset());

                    // Write with response
                    // Wait for previous write to complete
                    Log.v("Hex Write: ", writePartialFlash(chunk).toString());

                    // Sleep after 4 packets
                    count++;
                    if(count == 4){
                        count = 0;
                        Thread.sleep(100);
                    } else {
                        Thread.sleep(100);
                    }

                }

                // Finished Writing
                Log.v(TAG, "Flash Complete");

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
    private static byte[] recordToByteArray(String hexString, int offset){
        int len = hexString.length();
        byte[] data = new byte[(len/2) + 2];
        for(int i=0; i < len; i+=2){
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character.digit(hexString.charAt(i+1), 16));
        }

        data[(len/2)]   = (byte)(offset >> 8);
        data[(len/2)+1] = (byte)(offset & 0xFF);

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

        initialize(deviceAddress);
        readMemoryMap();
        // attemptPartialFlash(filePath);

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
                int startAddress = byteArray[3] << 24 | byteArray[2] << 16 | byteArray[1] << 8 | byteArray[0];
                int endAddress   = byteArray[11] << 24 | byteArray[10] << 16 | byteArray[9] << 8 | byteArray[8];
                Log.v(TAG, "Start: 0x" + Integer.toHexString(startAddress) + ", End: 0x" + Integer.toHexString(endAddress));

                // Log Hash
                bleReady = false;
                status = mBluetoothGatt.readCharacteristic(memoryMapChar);
                while(!bleReady);
                byteArray = memoryMapChar.getValue();
                Log.v(TAG, "Hash: " + byteArray.toString());

                i++;
            }


        } catch (Exception e){
            Log.e(TAG, e.toString());
        }

        return true;
    }

}
