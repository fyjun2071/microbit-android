package com.samsung.microbit.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;

/**
 * Created by Sam Kent on 01/11/2017.
 *
 * A Class to manipulate HEX files without modifying the Nordic DFU Library.
 * Focused towards stripping a file down to it's PXT section for use in Partial Flashing
 *
 */

public class HexUtils {
    private final static String TAG = HexUtils.class.getSimpleName();

    FileInputStream fis = null;
    BufferedReader reader = null;
    int BUFFER_LIMIT = 10000;

    String templateHash;
    String programHash;
    int sectionAddress;
    int magicAddress;

    int currentRecordType   = 0;
    int currentRecordOffset = 0;

    int magicLines = 0;

    /*
     Unused initializer
     */
    public void HexUtils(){
        // Hex Utils initialization
    }

    /*
        A function to open a hex file for reading
        @param filePath - A string locating the hex file in use
        @return true  - if file opens
                false - if file cannot be opened
     */
    public Boolean openHexFile(String filePath) throws IOException {
        // Open connection to hex file
        try {
            fis = new FileInputStream(filePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        // Create reader for hex file
        reader = new BufferedReader(new InputStreamReader(fis));
        return true;

    }

    public Boolean findMagic(String magic) throws IOException {
        String record;

        try {
            while ((record = reader.readLine()) != null) {
                // Inc magic lines
                magicLines++;

                // Record Type
                switch(getRecordType(record)) {
                    case 0: // Data
                        // Once the magic happens..
                        if(getRecordData(record).equals(magic)){
                            // Store Magic Address and Break
                            magicAddress = sectionAddress + getRecordAddress(record);
                            Log.v(TAG, "Magic Found!");
                            return true;
                        }
                        break;
                    case 1: // If record type is EOF break the loop
                        return false;
                    case 4:
                        // Recent Section
                        sectionAddress = Integer.parseInt(getRecordData(record) + "0000", 16);
                        break;
                }

                // Set mark to Magic record -1
                reader.mark(BUFFER_LIMIT);

            }
        } catch (Exception e){
            Log.e(TAG, e.toString());
        }

        // If magic is never found and there is no EOF file marker
        // Should never return here
        return false;
    }

    /*
        Used to get the data address from a record
        @param Record as a String
        @return Data address as a decimal
     */
    private int getRecordAddress(String record){
        String hexAddress = record.substring(3,7);
        return Integer.parseInt(hexAddress, 16);
    }

    /*
        Used to get the data length from a record
        @param Record as a String
        @return Data length as a decimal / # of chars
     */
    private int getRecordDataLength(String record){
        String hexLength = record.substring(1,3);
        int len = 2 * Integer.parseInt(hexLength, 16); // Num Of Bytes. Each Byte is represented by 2 chars hence 2*
        return len;
    }

    /*
    Used to get the record type from a record
    @param Record as a String
    @return Record type as a decimal
    */
    private int getRecordType(String record){
        String hexType = record.substring(7,9);
        return Integer.parseInt(hexType, 16);
    }

    /*
    Used to get the record type from the current record if it exists
    @return Record type as a decimal
    */
    public int getRecordType(){
        return currentRecordType;
    }

    /*
    Used to get the data from a record
    @param Record as a String
    @return Data
    */
    private String getRecordData(String record){
        int len = getRecordDataLength(record);
        return record.substring(9,9+len);
    }

    /*
    Used to return the data from the next record
     */
    public String getNextData() throws IOException {
        String data = reader.readLine();
        currentRecordType = getRecordType(data);
        currentRecordOffset = getRecordAddress(data);
        return getRecordData(data);
    }

    // Specific Functions Used For Partial Flashing Below
    /*
    Returns the template hash
    @return templateHash
     */
    public String getTemplateHash(){
        return templateHash;
    }

    /*
    Returns the program hash
    @return programHash
     */
    public String getProgramHash(){
        return programHash;
    }

    /*
    Find HEX Meta Data
     */
    public Boolean findHexMetaData(String filePath) throws IOException {
        // Open File
        if(!openHexFile(filePath)) return false;

        // Find PXT Meta Data
        if(!findMagic("708E3B92C615A841C49866C975EE5197")) return false;

        // Hashes
        String hashes = getNextData();
        templateHash  = hashes.substring(0,16);
        programHash   = hashes.substring(16,32);

        // Reset to Magic Address-1 - Ready for flashing
        reader.reset();

        // Log Hashes and Addresses
        Log.v(TAG, "Section Address: 0x" + Integer.toHexString(sectionAddress));
        Log.v(TAG, "Magic Address:   0x" + Integer.toHexString(magicAddress));
        Log.v(TAG, "Template Hash:   "   + templateHash);
        Log.v(TAG, "Program Hash:    "   + programHash);

        return true;

    }

    /*
    Find start address & start of PXT data
     */
    public Integer getSectionAddress(){
        return sectionAddress;
    }

    /*
    Get offset of current record
     */
    public int getRecordOffset(){
        return currentRecordOffset;
    }

    /*
    Set mark to beginning of page
     */
    public void setMark() throws IOException {
        reader.mark(BUFFER_LIMIT);
    }

    /*
    Rewind to start of page
     */
    public void rewind() throws IOException {
        reader.reset();
    }

    /*
    Number of lines / packets in file
     */
    public int numOfLines(String filePath) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath)));
        int lines = 0;
        while (!reader.readLine().contains("41140E2FB82FA2B")) lines++;
        reader.close();
        return lines;
    }

    /*
    Lines / packets before magic
     */
    public int getMagicLines()
    {
        return magicLines;
    }
}
