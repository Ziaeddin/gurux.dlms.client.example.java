/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gurux.dlms.client;

import gurux.dlms.GXDLMSClient;
import gurux.dlms.GXDLMSException;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.ObjectType;
import gurux.dlms.manufacturersettings.GXManufacturer;
import gurux.dlms.manufacturersettings.GXManufacturerCollection;
import gurux.dlms.objects.GXDLMSActionSchedule;
import gurux.dlms.objects.GXDLMSObject;
import gurux.dlms.objects.GXDLMSObjectCollection;
import gurux.dlms.objects.GXDLMSProfileGeneric;
import gurux.dlms.objects.IGXDLMSBase;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Array;

public class sampleclient 
{
    /**
    *
    * Show help.
    */
    static void ShowHelp()
    {
        System.out.println("GuruxDlmsSample reads data from the DLMS/COSEM device.");
        System.out.println("");
        System.out.println("GuruxDlmsSample /m=lgz /h=www.gurux.org /p=1000 [/s=] [/u]");        
        System.out.println(" /m=\t manufacturer identifier.");
        System.out.println(" /sp=\t serial port.");
        System.out.println(" /h=\t host name.");
        System.out.println(" /p=\t port number or name (Example: 1000 or COM1).");
        System.out.println(" /s=\t start protocol (IEC or DLMS).");        
        System.out.println(" /a=\t Authentication (None, Low, High).");
        System.out.println(" /pw=\t Password for authentication.");                
        System.out.println(" /t\t Trace messages.");                
        System.out.println(" /u\t Update meter settings from Gurux web portal.");
        System.out.println("Example:");
        System.out.println("Read LG device using TCP/IP connection.");
        System.out.println("GuruxDlmsSample /m=lgz /h=www.gurux.org /p=1000");
        System.out.println("Read LG device using serial port connection.");
        System.out.println("GuruxDlmsSample /m=lgz /sp=COM1 /s=DLMS");
    }
    
    static void trace(PrintWriter logFile, String text)
    {
        logFile.write(text);
        System.out.print(text);
    }

    static void traceLn(PrintWriter logFile, String text)
    {        
        logFile.write(text + "\r\n");
        System.out.print(text + "\r\n");
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) 
    {   
        // Gurux example server parameters.
        // /m=lgz /h=localhost /p=4060 
        // /m=grx /h=localhost /p=4061
        GXCommunicate com = null;
        PrintWriter logFile = null;        
        try
        {
            logFile = new PrintWriter(new BufferedWriter(new FileWriter("logFile.txt")));
            String path = "ManufacturerSettings";
            //Get manufacturer settings from Gurux web service if not installed yet.
            //This is something that you do not nesessary seed. You can 
            //hard code the settings. This is only for demostration.
            //Use hard coded settings like this:
            // GXDLMSClient cl = new GXDLMSClient(true,             
            // (byte) 0x21,            
            // (byte) 0x3,            
            // Authentication.None,             
            // null,
            // InterfaceType.General);

            try
            {
                if (GXManufacturerCollection.isFirstRun(path))
                {
                    GXManufacturerCollection.updateManufactureSettings(path);
                }
            }
            catch(Exception ex)
            {
                System.out.println(ex.toString());
            }
            //4059 is Official DLMS port.
            String id = "", host = "", port = "4059", pw = "";
            boolean trace = false, iec = true, isSerial = false;
            Authentication auth = Authentication.NONE;            
            for (String it : args)
            {
                String item = it.trim().toLowerCase();
                if (item.compareToIgnoreCase("/u") == 0)//Update
                {
                    //Get latest manufacturer settings from Gurux web server.
                    GXManufacturerCollection.updateManufactureSettings(path);
                }
                else if(item.startsWith("/m="))//Manufacturer
                {
                    id = item.replaceFirst("/m=", "");
                }
                else if(item.startsWith("/h=")) //Host
                {
                    host = item.replaceFirst("/h=", "");              
                }
                else if (item.startsWith("/p="))// TCP/IP Port
                {
                    port = item.replaceFirst("/p=", "");
                }
                else if (item.startsWith("/sp="))//Serial Port
                {
                    port = item.replaceFirst("/sp=", "");
                    isSerial = true;
                }
                else if (item.startsWith("/t"))//Are messages traced.
                {                    
                    trace = true;
                }
                else if (item.startsWith("/s="))//Start
                {
                    String tmp = item.replaceFirst("/s=", "");
                    iec = !tmp.equals("dlms");
                }
                else if (item.startsWith("/a="))//Authentication
                {
                    auth = Authentication.valueOf(it.trim().replaceFirst("/a=", ""));                    
                }
                else if (item.startsWith("/pw="))//Password
                {
                    pw = it.trim().replaceFirst("/pw=", "");
                }
                else
                {
                    ShowHelp();
                    return;
                }
            }
            if (id.isEmpty() || port.isEmpty() || (!isSerial && host.isEmpty()))
            {
                ShowHelp();
                return;
            }
            GXDLMSClient dlms = new GXDLMSClient();
            GXManufacturerCollection items = new GXManufacturerCollection();
            GXManufacturerCollection.readManufacturerSettings(items, path);
            GXManufacturer man = items.findByIdentification(id);
            dlms.setObisCodes(man.getObisCodes());
            com = new GXCommunicate(5000, dlms, man, iec, auth, pw);                        
            com.Trace = trace;
            if (isSerial)
            {
             //TODO:   com.initializeSerial(port);
            }
            else
            {
                com.initializeSocket(host, Integer.parseInt(port));
            }            
                                
            System.out.println("Reading association view");
            byte[] reply = com.readDataBlock(dlms.getObjectsRequest());
            GXDLMSObjectCollection objects = dlms.parseObjects(reply, true);                                                
            //Read all attributes from all objects.
            for(GXDLMSObject it : objects)
            {
                if (!(it instanceof IGXDLMSBase))
                {
                    //If interface is not implemented.
                    System.out.println("Unknown Interface: " + it.getObjectType().toString());
                    continue;
                }
                
                if (it instanceof GXDLMSProfileGeneric)
                {
                    //Profile generic are read later 
                    // because it might take so long time
                    // and this is only a example.
                    continue;
                }                
                traceLn(logFile, "-------- Reading " + 
                        it.getClass().getSimpleName() + " " + 
                        it.getDescription());
                for(int pos : ((IGXDLMSBase) it).GetAttributeIndexToRead())
                {
                    try
                    {
                        Object val = com.readObject(it, pos);
                        if (val instanceof Object[])
                        {
                            String str = "";
                            for(int pos2 = 0; pos2 != Array.getLength(val); ++pos2)
                            {
                                if (str.equals(""))
                                {
                                    str += ", ";
                                }
                                str += Array.get(val, pos2).toString();
                            }
                            val = str;
                        }
                        traceLn(logFile, "Index: " + pos + " Value: " + val);                    
                    }
                    catch(GXDLMSException ex)
                    {
                        //Continue reading if device returns access denied error.
                        if (ex.getErrorCode() == 3)
                        {
                            continue;
                        }
                        throw ex;
                    }
                }                
            }            
            ///////////////////////////////////////////////////////////////////
            //Get profile generics headers and data.
            Object[] cells;
            GXDLMSObjectCollection profileGenerics = objects.getObjects(ObjectType.PROFILE_GENERIC);
            for(GXDLMSObject it : profileGenerics)
            {               
                traceLn(logFile, "-------- Reading " + 
                        it.getClass().getSimpleName() + " " + 
                        it.getDescription());
                GXDLMSProfileGeneric pg = (GXDLMSProfileGeneric) it;
                //Read columns.
                GXDLMSObject[] columns = (GXDLMSObject[]) com.readObject(pg, 3);
                for(GXDLMSObject col : columns)
                {
                    trace(logFile, col.getLogicalName() + " | ");
                }
                traceLn(logFile, "");             
                ///////////////////////////////////////////////////////////////////
                //Read last day.
                java.util.Calendar start = java.util.Calendar.getInstance();
                start.set(java.util.Calendar.HOUR_OF_DAY, 0); // set hour to midnight 
                start.set(java.util.Calendar.MINUTE, 0); // set minute in hour 
                start.set(java.util.Calendar.SECOND, 0); // set second in minute 
                start.set(java.util.Calendar.MILLISECOND, 0); 
                java.util.Calendar end = java.util.Calendar.getInstance();
                start.add(java.util.Calendar.DATE, -1);    
                GXDLMSObject sorted = pg.getSortObject();
                if (sorted == null)
                {
                    sorted = pg.getCaptureObjects()[0];
                }
                cells = com.readRowsByRange(it, sorted, start.getTime(), end.getTime());
                for(Object rows : cells)
                {
                    for(Object cell : (Object[]) rows)
                    {
                        if (cell instanceof byte[])
                        {
                            System.out.print(GXDLMSClient.toHex((byte[]) cell) + " | ");
                        }
                        else
                        {
                            trace(logFile, cell + " | ");
                        }
                    }
                    traceLn(logFile, "");
                }
                
                ///////////////////////////////////////////////////////////////////
                //Read first item.
                traceLn(logFile, "First row");
                int first = 0;
                int count = 1;
                cells = com.readRowsByEntry(pg, first, count);
                for(Object rows : cells)
                {
                    for(Object cell : (Object[]) rows)
                    {
                        if (cell instanceof byte[])
                        {
                            trace(logFile, GXDLMSClient.toHex((byte[]) cell) + " | ");
                        }
                        else
                        {
                            trace(logFile, cell + " | ");
                        }
                    }
                    traceLn(logFile, "");
                }                
            }       
        }
        catch(Exception ex)
        {            
            System.out.println(ex.toString());
        }
        finally
        {
            if (logFile != null)
            {
                logFile.close();
            }            
            try
            {
                ///////////////////////////////////////////////////////////////
                //Disconnect.
                if (com != null)
                {                    
                    com.close();
                }
            }
            catch(Exception Ex2)
            {
                System.out.println(Ex2.toString());
            }
        }
    }
}
