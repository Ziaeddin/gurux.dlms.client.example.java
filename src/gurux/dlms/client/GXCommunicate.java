//
// --------------------------------------------------------------------------
//  Gurux Ltd
// 
//
//
// Filename:        $HeadURL$
//
// Version:         $Revision$,
//                  $Date$
//                  $Author$
//
// Copyright (c) Gurux Ltd
//
//---------------------------------------------------------------------------
//
//  DESCRIPTION
//
// This file is a part of Gurux Device Framework.
//
// Gurux Device Framework is Open Source software; you can redistribute it
// and/or modify it under the terms of the GNU General Public License 
// as published by the Free Software Foundation; version 2 of the License.
// Gurux Device Framework is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
// See the GNU General Public License for more details.
//
// More information of Gurux products: http://www.gurux.org
//
// This code is licensed under the GNU General Public License v2. 
// Full text may be retrieved at http://www.gnu.org/licenses/gpl-2.0.txt
//---------------------------------------------------------------------------

package gurux.dlms.client;

import gurux.common.IGXMedia;
import gurux.common.ReceiveParameters;
import gurux.dlms.enums.RequestTypes;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.Authentication;
import gurux.dlms.GXDLMSClient;
import gurux.dlms.GXDLMSException;
import gurux.dlms.manufacturersettings.*;
import gurux.dlms.objects.GXDLMSCaptureObject;
import gurux.dlms.objects.GXDLMSObject;
import gurux.net.GXNet;
import gurux.net.NetworkType;
import gurux.serial.GXSerial;
import gurux.io.Parity;
import gurux.io.StopBits;
import java.io.ByteArrayOutputStream;
import java.util.AbstractMap;
import java.util.Date;
import java.util.List;

public class GXCommunicate
{
    IGXMedia Media;
    public boolean Trace = false;
    long ConnectionStartTime;
    GXManufacturer manufacturer;
    GXDLMSClient dlms;
    boolean iec;
    java.nio.ByteBuffer replyBuff;
    int WaitTime = 0;

    public GXCommunicate(int waitTime, gurux.dlms.GXDLMSClient dlms, GXManufacturer manufacturer, boolean iec, Authentication auth, String pw, IGXMedia media) throws Exception
    {             
        Media = media;
        WaitTime = waitTime;
        this.dlms = dlms;
        this.manufacturer = manufacturer;
        this.iec = iec;
        dlms.setInterfaceType(manufacturer.getUseIEC47() ? InterfaceType.NET : InterfaceType.GENERAL);
        dlms.setUseLogicalNameReferencing(manufacturer.getUseLogicalNameReferencing());
        Object val = manufacturer.getAuthentication(Authentication.NONE).getClientID();
        long value = Long.parseLong(val.toString());
        value = value << 1 | 1;
        dlms.setClientID(GXManufacturer.convertTo(value, val.getClass()));
        GXServerAddress serv = manufacturer.getServer(HDLCAddressType.DEFAULT);        
        val = GXManufacturer.countServerAddress(serv.getHDLCAddress(), serv.getPhysicalAddress(), serv.getLogicalAddress());        
        dlms.setServerID(val);
        dlms.setAuthentication(auth);
        dlms.setPassword(pw.getBytes("ASCII"));        
        System.out.println("Authentication: " + auth);
        System.out.println("ClientID: 0x" + Integer.toHexString(Integer.parseInt(dlms.getClientID().toString())));
        System.out.println("ServerID: 0x" + Integer.toHexString(Integer.parseInt(dlms.getServerID().toString())));
        if (dlms.getInterfaceType() == InterfaceType.NET)
        {
            replyBuff = java.nio.ByteBuffer.allocate(8 + 1024);
        }
        else
        {
            replyBuff = java.nio.ByteBuffer.allocate(100);
        }
    }

    void close() throws Exception
    {
        if (Media != null)
        {
            System.out.println("DisconnectRequest");
            readDLMSPacket(dlms.disconnectRequest());
            Media.close();
        }
    }

    /*
     * Read DLMS Data from the device.
     * If access is denied return null.
     */
    public byte[] readDLMSPacket(byte[] data) throws Exception
    {
        if (data == null || data.length == 0)
        {
            return null;
        }
        Object eop = (byte) 0x7E;
        //In network connection terminator is not used.
        if (dlms.getInterfaceType() == InterfaceType.NET && Media instanceof GXNet)
        {
            eop = null;
        }
        Integer pos = 0;
        boolean succeeded = false;        
	ReceiveParameters<byte[]> p = new ReceiveParameters<byte[]>(byte[].class);
        p.setAllData(true);
        p.setEop(eop);
        p.setCount(5);
        p.setWaitTime(WaitTime);        
        synchronized (Media.getSynchronous())
        {
            while (!succeeded && pos != 3)
            {
                if (Trace)
                {   
                    System.out.println("<- " + GXDLMSClient.toHex(data));
                }
                Media.send(data, null);
                succeeded = Media.receive(p);
                if (!succeeded)
                {
                    //Try to read again...
                    if (++pos != 3)
                    {
                        System.out.println("Data send failed. Try to resend " + pos.toString() + "/3");
                        continue;
                    }   
                    throw new Exception("Failed to receive reply from the device in given time.");
                }
            }
            //Loop until whole Cosem packet is received.                
            while (!dlms.isDLMSPacketComplete(p.getReply()))
            {
                if (p.getEop() == null)
                {
                    p.setCount(1);
                }
                if (!Media.receive(p))
                {
                    throw new Exception("Failed to receive reply from the device in given time.");
                }
            }
        }
        if (Trace)
        {
            System.out.println("-> " + GXDLMSClient.toHex(p.getReply()));
        }
        Object[][] errors = dlms.checkReplyErrors(data, p.getReply());
        if (errors != null)
        {
            throw new GXDLMSException((Integer) errors[0][0]);
        }
        return p.getReply();       
    }
    
    static String toHex(byte[] bytes) 
    {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 3];
        int tmp;
        for (int pos = 0; pos != bytes.length; ++pos) 
        {
            tmp = bytes[pos] & 0xFF;
            hexChars[pos * 3] = hexArray[tmp >> 4];
            hexChars[pos * 3 + 1] = hexArray[tmp & 0x0F];
            hexChars[pos * 3 + 2] = ' ';
        }
        return new String(hexChars);
    }
    
    /**
     * Opens connection again.
     * @throws Exception
     */
    void reOpen() throws Exception
    {
        /*
        if (manufacturer.getInactivityMode() == InactivityMode.REOPENACTIVE && serial != null && java.util.Calendar.getInstance().getTimeInMillis() - ConnectionStartTime > 40000)
        {
            String port = serial.getName();
            close();
            initializeSerial(port);
        } 
        * */
    }

    /**
     * Reads next data block.
     * @param data
     * @return
     * @throws Exception
     */
    @SuppressWarnings("unused")
	byte[] readDataBlock(byte[] data) throws Exception
    {
        if (data.length == 0)
        {
            return new byte[0];
        }
        reOpen();
        byte[] reply = readDLMSPacket(data);
        ByteArrayOutputStream allData = new ByteArrayOutputStream();
        java.util.Set<RequestTypes> moredata = dlms.getDataFromPacket(reply, allData);
        //If there is nothing to send.
        if (allData == null)
        {
            return new byte[0];
        }
        int maxProgress = dlms.getMaxProgressStatus(allData);        
        int lastProgress = 0;
        float progress;
        while (!moredata.isEmpty())
        {
            while (moredata.contains(RequestTypes.FRAME))
            {                
                data = dlms.receiverReady(RequestTypes.FRAME);
                reply = readDLMSPacket(data);
                //Show progress.
                if (maxProgress != 1)
                {
                    progress = dlms.getCurrentProgressStatus(allData);
                    progress = progress / maxProgress * 80;
                    if (lastProgress != (int) progress)
                    {
                        if (Trace)
                        {
                            for(int pos = lastProgress; pos < (int) progress; ++pos)
                            {
                                System.out.print("-");
                            }
                        }
                        lastProgress = (int) progress;
                    }
                }                
                if (!dlms.getDataFromPacket(reply, allData).contains(RequestTypes.FRAME))
                {
                    moredata.remove(RequestTypes.FRAME);                    
                    break;
                }
            }
            reOpen();
            if (moredata.contains(RequestTypes.DATABLOCK))
            {             
                //Send Receiver Ready.
                data = dlms.receiverReady(RequestTypes.DATABLOCK);
                reply = readDLMSPacket(data);
                moredata = dlms.getDataFromPacket(reply, allData);
                //Show progress.
                if (maxProgress != 1)
                {
                    progress = dlms.getCurrentProgressStatus(allData);
                    if (Trace)
                    {
                        progress = progress / maxProgress * 80;
                        if (lastProgress != (int) progress)
                        {
                            for(int pos = lastProgress; pos < (int) progress; ++pos)
                            {
                                System.out.print("+");
                            }
                            lastProgress = (int) progress;
                        }
                    }
                 }
            }
        }
        if (maxProgress > 1)
        {
            if (Trace)
            {
                System.out.println("");
            }
        }
        return allData.toByteArray();
    }

    /**
     * Initializes connection.
     * @param port
     * @throws Exception
     */
    void initializeConnection() throws Exception
    {       
        Media.open();
        if (Media instanceof GXSerial)
        {
            if (iec)
            {
                ReceiveParameters<byte[]> p = new ReceiveParameters<byte[]>(byte[].class);
                p.setAllData(false);
                p.setEop((byte) '\n');
                p.setWaitTime(WaitTime);
                String data;
                String replyStr;
                synchronized (Media.getSynchronous())
                {
                    data = "/?!\r\n";
                    if (Trace)
                    {   
                        System.out.println("<- " + GXDLMSClient.toHex(data.getBytes("ASCII")));
                    }
                    Media.send(data, null);
                    if (!Media.receive(p))
                    {
                        throw new Exception("Invalid meter type.");                    
                    }
                    if (Trace)
                    {   
                        System.out.println("-> " + GXDLMSClient.toHex(p.getReply()));
                    }
                    //If echo is used.
                    replyStr = new String(p.getReply());
                    if (data.equals(replyStr))
                    {
                        if (!Media.receive(p))
                        {
                            throw new Exception("Invalid meter type.");                    
                        }
                        if (Trace)
                        {   
                            System.out.println("-> " + GXDLMSClient.toHex(p.getReply()));
                        }
                        replyStr = new String(p.getReply());
                    }
                }
                if (replyStr.length() == 0 || replyStr.charAt(0) != '/')
                {
                    throw new Exception("Invalid responce.");
                }
                String manufactureID = replyStr.substring(1, 3);
                if (manufacturer.getIdentification().compareToIgnoreCase(manufactureID) != 0)
                {
                    throw new Exception("Manufacturer " + manufacturer.getIdentification() + " expected but " + manufactureID + " found.");
                }
                int bitrate = 0;
                char baudrate = replyStr.charAt(4);
                switch (baudrate)
                {
                    case '0':
                        bitrate = 300;
                        break;
                    case '1':
                        bitrate = 600;
                        break;
                    case '2':
                        bitrate = 1200;
                        break;
                    case '3':
                        bitrate = 2400;
                        break;
                    case '4':
                        bitrate = 4800;
                        break;
                    case '5':
                        bitrate = 9600;
                        break;
                    case '6':
                        bitrate = 19200;
                        break;
                    default:
                        throw new Exception("Unknown baud rate.");
                }
                System.out.println("Bitrate is : " + bitrate);
                //Send ACK
                //Send Protocol control character
                byte controlCharacter = (byte)'2';// "2" HDLC protocol procedure (Mode E)
                //Send Baudrate character
                //Mode control character
                byte ModeControlCharacter = (byte)'2';//"2" //(HDLC protocol procedure) (Binary mode)
                //Set mode E.
                byte[] tmp = new byte[] { 0x06, controlCharacter, (byte)baudrate, ModeControlCharacter, 13, 10 };
                GXSerial serial = (GXSerial) Media;
                synchronized (Media.getSynchronous())
                {
                    if (Trace)
                    {   
                        System.out.println("<- " + GXDLMSClient.toHex(tmp));
                    }
                    serial.setBaudRate(bitrate);
                    Media.send(tmp, null);
                    if (!Media.receive(p))
                    {
                        throw new Exception("Invalid meter type.");                    
                    }
                }
                serial.setDataBits(8);
                serial.setParity(Parity.NONE);
                serial.setStopBits(StopBits.ONE);
            }            
            ConnectionStartTime = java.util.Calendar.getInstance().getTimeInMillis();
            byte[] reply = null;
            byte[] data = dlms.SNRMRequest();
            if (data != null)
            {
                reply = readDLMSPacket(data);
                //Has server accepted client.
                dlms.parseUAResponse(reply);
                
                //Allocate buffer to same size as transmit buffer of the meter.
                //Size of replyBuff is payload and frame (Bop, EOP, crc).            
                int size = (int) ((((Number)dlms.getLimits().getMaxInfoTX()).intValue() & 0xFFFFFFFFL) + 40);
                replyBuff = java.nio.ByteBuffer.allocate(size);
            }
            //Generate AARQ request.
            //Split requests to multible packets if needed.
            //If password is used all data might not fit to one packet.
            for (byte[] it : dlms.AARQRequest(null))
            {            
                reply = readDLMSPacket(it);
            }
            //Parse reply.
            dlms.parseAAREResponse(reply);
            //Get challenge Is HLS authentication is used.
            if (dlms.getIsAuthenticationRequired())
            {
                reply = readDLMSPacket(dlms.getApplicationAssociationRequest());
                dlms.parseApplicationAssociationResponse(reply);
            }
        }
    }   

    /**
     * Reads selected DLMS object with selected attribute index.
     * @param item
     * @param attributeIndex
     * @return
     * @throws Exception
     */
    public Object readObject(GXDLMSObject item, int attributeIndex) throws Exception
    {
        byte[] data = dlms.read(item.getName(), item.getObjectType(), attributeIndex)[0];
        data = readDataBlock(data);
        return dlms.updateValue(data, item, attributeIndex);
    }
    
     /**
     * Writes value to DLMS object with selected attribute index.
     * @param item
     * @param attributeIndex
     * @return
     * @throws Exception
     */
    public void writeObject(GXDLMSObject item, int attributeIndex) throws Exception
    {
        byte[] data = dlms.write(item, attributeIndex)[0];
        readDataBlock(data);       
    }

    /*
     * Returns columns of profile Generic.
     */
    public List<AbstractMap.SimpleEntry<GXDLMSObject, GXDLMSCaptureObject>> GetColumns(GXDLMSObject pg) throws Exception
    {
        Object entries = readObject(pg, 7);
        GXObisCode code = manufacturer.getObisCodes().findByLN(pg.getObjectType(), pg.getLogicalName(), null);
        if (code != null)
        {
            System.out.println("Reading Profile Generic: " + pg.getLogicalName() + " " + code.getDescription() + " entries:" + entries.toString());
        }
        else
        {
            System.out.println("Reading Profile Generic: " + pg.getLogicalName() + " entries:" + entries.toString());
        }
        byte[] data = dlms.read(pg.getName(), pg.getObjectType(), 3)[0];
        data = readDataBlock(data);
        return dlms.parseColumns(data);
    }

    /**
     * Read Profile Generic's data by entry start and count.
     * @param pg
     * @param index
     * @param count
     * @return
     * @throws Exception
     */
    public Object[] readRowsByEntry(GXDLMSObject pg, int index, int count) throws Exception
    {
        byte[] data = dlms.readRowsByEntry(pg.getName(), index, count);
        data = readDataBlock(data);
        return (Object[])dlms.updateValue(data, pg, 2);        
    }

    /**
     * Read Profile Generic's data by range (start and end time).
     * @param pg
     * @param sortedItem
     * @param start
     * @param end
     * @return
     * @throws Exception
     */
    public Object[] readRowsByRange(GXDLMSObject pg, GXDLMSObject sortedItem, Date start, Date end) throws Exception
    {
        byte[] data = dlms.readRowsByRange(pg.getName(), sortedItem.getLogicalName(), pg.getObjectType(), sortedItem.getVersion(), start, end);
        data = readDataBlock(data);
        return (Object[])dlms.updateValue(data, pg, 2);        
    }
}