package com.example.bertier.ocat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;
import android.view.View;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.content.Context.BATTERY_SERVICE;
import static android.os.SystemClock.elapsedRealtimeNanos;

public class NearbyWorker extends Thread {
    private Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int thisAPI = APIs.NEARBY;
    private ConnectionsClient connectionsClient;

    private Context context;
    private Handler mHandler;
    public Handler workerHandler;
    /*Strings*/
    private final String appName="Ocat";
    private String namePhone;
    private String comments="";
    private String distance="";
    private String topology="";
    private Map<String,String> connectedEndpoints = new HashMap<>();
    private SimpleArrayMap<String,String> tmpEndpoints=new SimpleArrayMap<>();
    /*Data Structures*/
    private SimpleArrayMap<Long, Payload> incomingPayloads = new SimpleArrayMap<>();
    private SimpleArrayMap<Long, Payload> outgoingPayloads = new SimpleArrayMap<>();
    private List<Long> transferUpdatesTimes = new ArrayList<>();
    private List<Integer> transferUpdatesLinkSpeed = new ArrayList<>();
    private List<Integer> transferUpdatesFrequency = new ArrayList<>();
    private List<Integer> transferUpdatesRSSI = new ArrayList<>();
    private List<Long> transferUpdatesBytes = new ArrayList<>();
    private List<Location> transferUpdatesPositions = new ArrayList<>();
    private File[] randomFilesToSend;

    VibrationEffect patternConnect=VibrationEffect.createWaveform(new long[]{0, 50, 1000},-1);
    VibrationEffect patternDisconnect=VibrationEffect.createWaveform(new long[]{0, 150, 100,150},-1);
    VibrationEffect patternEnd=VibrationEffect.createOneShot(2000,255);
    /* Special classes */
    private Location currentEstimateLocation;
    private LocationManager locationManager;
    private LogWriter logStream;
    private BatteryManager bm;
    private RandomFileFactory fileFactory;
    private boolean nearbyOn=false;
    private boolean reuseFile=true;
    private boolean reconnect=true;
    private boolean lineOfSight=true;
    private boolean instantSpeed=false;
    private boolean onGoingBenchmark=false;
    private boolean multiMode;
    private boolean advertiser;
    private int currentFile=0;
    private int currentLoop=0;
    private int lengthLoop;
    private int nbFilesSelected;
    private int ack=0;
    private final int numberRetry=5;
    private int currentRetry=0;
    private boolean sendingSuccess=true;
    // Define a listener that responds to location updates
    WifiManager wifiManager;
    WifiInfo wifiInfo;
    private Vibrator vibrator;
    private Ringtone r;

    NearbyWorker(Context c, Handler h, boolean multi, boolean advertise, String topology) {
        context=c;
        mHandler=h;
        PreferenceManager.setDefaultValues(context,R.xml.preference,false);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        connectionsClient = Nearby.getConnectionsClient(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        r = RingtoneManager.getRingtone(c.getApplicationContext(), notification);
        try {
            logStream=new LogWriter(context);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            locationManager= (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            String locationProvider = LocationManager.GPS_PROVIDER;
            locationManager.requestLocationUpdates(locationProvider, 0, 0, locationListener);
        }catch (SecurityException e){
            //TODO: Treat when user doesn't grant location access
            Log.w(appName,"Can't find location: "+e.toString());
        }
        bm = (BatteryManager)context.getSystemService(BATTERY_SERVICE);
        fileFactory = new RandomFileFactory(context.getCacheDir());
        multiMode=multi;
        advertiser=advertise;
        if(topology.equals("Star")){
            STRATEGY=Strategy.P2P_STAR;
        }else if(topology.equals("Cluster")){
            STRATEGY=Strategy.P2P_CLUSTER;
        }else if(topology.equals("1-to-1")){
            STRATEGY=Strategy.P2P_POINT_TO_POINT;
        }
        if(multi){
            //STRATEGY = Strategy.P2P_STAR;
            if(advertiser){
                UIsetText(WidgetEnum.find_phones,"Start Advertising");
            }else{
                UIsetText(WidgetEnum.find_phones,"Start Discovery");
                UIsetVisibility(WidgetEnum.start_benchmark, View.GONE);
            }
        }

    }

    private LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            // Called when a new location is found by the network location provider.
            currentEstimateLocation=location;
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {}
    };



    private PayloadCallback payloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, Payload payload) {
                    Message m = new Message();
                    m.obj = payload;
                    m.what=ThreadCommands.PAYLOAD_UPDATE;
                    workerHandler.sendMessage(m);
                }

                @SuppressLint("DefaultLocale")
                @Override
                public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
                    Message m = new Message();
                    m.obj=update;
                    m.what=ThreadCommands.TRANSFER_UPDATE;
                    workerHandler.sendMessage(m);
                }
            };


    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                    logStream.logDiscoverPeer(elapsedRealtimeNanos(),info.getEndpointName());
                    Log.i(appName, "onEndpointFound: endpoint found, connecting to "+endpointId);
                    connectionsClient.requestConnection(namePhone, endpointId, connectionLifecycleCallback);
                }

                @Override
                public void onEndpointLost(String endpointId) {
                    logStream.logError("onEnpointLost "+endpointId);
                    resetBench();
                    nearbyOn=false;
                    Log.i(appName,"EndpointLost "+endpointId);
                }
            };


    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    long time=elapsedRealtimeNanos();
                    //currentTargetName = connectionInfo.getEndpointName();
                    tmpEndpoints.put(endpointId,connectionInfo.getEndpointName());
                    Log.i(appName, "onConnectionInitiated: accepting connection");
                    logStream.logConnectionStart(thisAPI,time,connectionInfo.getEndpointName(),"");
                    connectionsClient.acceptConnection(endpointId, payloadCallback);
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result) {
                    long time=elapsedRealtimeNanos();
                    if (result.getStatus().isSuccess()) {
                        connectedEndpoints.put(endpointId,tmpEndpoints.get(endpointId));
                        Log.i(appName, "onConnectionResult: connection successful");
                        logStream.logConnectionEstablished(thisAPI,time,connectedEndpoints.get(endpointId),"");
                        vibrator.vibrate(patternConnect);
                        if(multiMode){
                            if(advertiser){
                                UIsetText(WidgetEnum.status,"connected to "+connectedEndpoints.size()+" device(s).");
                            }else{
                                UIsetText(WidgetEnum.status,"connected to advertiser "+connectedEndpoints.get(endpointId));
                                connectionsClient.stopDiscovery();
                            }

                        }else {
                            connectionsClient.stopDiscovery();
                            connectionsClient.stopAdvertising();
                            UIsetText(WidgetEnum.status,"connected to "+connectedEndpoints.get(endpointId));
                            UIsetEnabled(WidgetEnum.start_benchmark,true);
                        }

                        if(randomFilesToSend != null){
                            fireNextFile();
                        }else{
                            if(multiMode && advertiser){
                                UIsetEnabled(WidgetEnum.start_benchmark,true);
                            }
                        }
                    } else {
                        logStream.logError("onConnectionResult: connection failed "+tmpEndpoints.get(endpointId));
                        UIsetText(WidgetEnum.status,"Connection failed.");
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    String disconnectedName = connectedEndpoints.remove(endpointId);
                    logStream.logDisconnection(disconnectedName);
                    Log.i(appName, "onDisconnected: disconnected from "+disconnectedName);
                    vibrator.vibrate(patternDisconnect);
                    if(multiMode){
                        if(connectedEndpoints.size()>0){
                            UIsetText(WidgetEnum.status,"connected to "+connectedEndpoints.size()+" device(s).");
                        }else{
                            UIsetText(WidgetEnum.status,"disconnected from all devices.");
                        }
                    }else {
                        UIsetText(WidgetEnum.status, "Disconnected from " + disconnectedName);
                    }
                    UIsetProgress(WidgetEnum.transferBar,0);
                    if(reconnect && onGoingBenchmark){
                        Message m = new Message();
                        m.what = ThreadCommands.RECONNECT;
                        workerHandler.sendMessage(m);
                    }else {
                        resetBench();
                        nearbyOn = false;
                        if(advertiser){
                            UIsetText(WidgetEnum.find_phones, "Start Advertising");
                        }else{
                            UIsetText(WidgetEnum.find_phones, "Start Discovery");
                        }

                        UIsetEnabled(WidgetEnum.start_benchmark,false);
                    }
                }
            };


    private void payloadReceived(Payload payload){
        if (payload.getType() == Payload.Type.BYTES) {
            String payloadMessage = new String(payload.asBytes());
            int colonIndex = payloadMessage.indexOf(':');
            int underscoreIndex = payloadMessage.indexOf('_');
            int slashIndex = payloadMessage.indexOf('/');
            //Incoming file notice
            if(colonIndex > -1){
                wifiInfo = wifiManager.getConnectionInfo();
                if(wifiInfo.getLinkSpeed() > 0){
                    UIsetText(WidgetEnum.loopStatus,"Link: "+wifiInfo.getFrequency()+"MHz|"+wifiInfo.getLinkSpeed()+" Mbps|"+wifiInfo.getRssi()+" dBm");
                }
                String filename = payloadMessage.substring(colonIndex + 1);
                UIsetText(WidgetEnum.score,"Receiving "+filename);
                int sizeFile= Integer.parseInt(payloadMessage.substring(0,colonIndex));
                logStream.logIncomingFile(sizeFile,filename);
                UIsetProgress(WidgetEnum.transferBar,0);
                UIsetEnabled(WidgetEnum.start_benchmark,false);
            //New benchmark notice
            }else if(underscoreIndex > -1){
                int secondSlashIndex=payloadMessage.indexOf('_',underscoreIndex+1);
                String comment = payloadMessage.substring(0,underscoreIndex);
                String distance=payloadMessage.substring(underscoreIndex+1,secondSlashIndex);
                String currentTargetName=payloadMessage.substring(secondSlashIndex+1);
                int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                logStream.logBenchmark(true,comment,distance,lineOfSight,batLevel,currentTargetName);
                onGoingBenchmark=true;
                UIsetText(WidgetEnum.distance,distance);
                UIsetText(WidgetEnum.comments,comment);
                UIsetText(WidgetEnum.status,"receiving from "+currentTargetName);
                askNextFile("");
            //File ack + hash
            }else if(slashIndex > -1){
                ack++;
                String remoteHash = payloadMessage.substring(slashIndex+1);
                if(!remoteHash.isEmpty()) {
                    try {
                        String localHash=RandomFileFactory.getSHA1(randomFilesToSend[currentFile-1]);
                        if(!localHash.equals(remoteHash)) {
                            logStream.logError("local:"+localHash+" remote:"+remoteHash+" do not match");
                            //UIsetText(WidgetEnum.score,"Hash do not match. Abort.");
                            //sendMessage("end");
                            sendingSuccess=false;
                            logStream.flush();
                        }
                    } catch (NoSuchAlgorithmException | IOException e) {
                        logStream.logError(e.toString());
                    }
                }
                if(ack >= connectedEndpoints.size()){
                    ack=0;
                    if(sendingSuccess){
                        currentRetry=0;
                        fireNextFile();
                    }else{
                        //re-init the success variable
                        sendingSuccess=true;
                        currentRetry++;
                        if(currentRetry < numberRetry){
                            sendFile();
                        }else{
                            logStream.logError("toomanyretry");
                            currentRetry=0;
                            fireNextFile();
                        }
                    }
                }

            }else if(payloadMessage.equals("end")){
                UIsetText(WidgetEnum.score,"Benchmark done");
                vibrator.vibrate(patternEnd);
                r.play();
                UIsetProgress(WidgetEnum.transferBar,0);
                UIsetEnabled(WidgetEnum.start_benchmark,true);
                logStream.logEndBenchmark();
                logStream.flush();
                onGoingBenchmark=false;
            }

        } else if (payload.getType() == Payload.Type.FILE) {
            // Add this to our tracking map, so that we can retrieve the payload later.
            incomingPayloads.put(payload.getId(), payload);
        }
    }

    private void transferUpdate(PayloadTransferUpdate update){
        //Standard way of measuring intervals
        Long update_time=elapsedRealtimeNanos();
        switch(update.getStatus()) {
            case PayloadTransferUpdate.Status.IN_PROGRESS:
                long size = update.getTotalBytes();
                if (size == -1) {
                    // Stream payload
                    return;
                }
                if(incomingPayloads.containsKey(update.getPayloadId())) {
                    wifiInfo = wifiManager.getConnectionInfo();
                    int speed= wifiInfo.getLinkSpeed();
                    if(speed != -1){
                        transferUpdatesLinkSpeed.add(speed);
                        transferUpdatesFrequency.add(wifiInfo.getFrequency());
                        transferUpdatesRSSI.add(wifiInfo.getRssi());
                    }
                    transferUpdatesTimes.add(update_time);
                    transferUpdatesBytes.add(update.getBytesTransferred());
                    try {
                        currentEstimateLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        transferUpdatesPositions.add(currentEstimateLocation);
                    }catch (SecurityException e){
                        transferUpdatesPositions.add(null);
                    }
                    int s= transferUpdatesTimes.size()-1;
                    if(instantSpeed && s > 1) {
                        long bytesReceived, timeDiff;
                        bytesReceived=transferUpdatesBytes.get(s)-transferUpdatesBytes.get(s-1);
                        timeDiff=transferUpdatesTimes.get(s)-transferUpdatesTimes.get(s-1);
                        double fileTransferSpeed=(bytesReceived/1000000.0) / ((double)(timeDiff)/1000.0);
                        UIsetText(WidgetEnum.instantSpeed,String.format( "%.2f", fileTransferSpeed )+" MB/s");
                    }
                }
                Double progress=100.0 * update.getBytesTransferred() / (double) size;
                UIsetProgress(WidgetEnum.transferBar,progress.intValue());
                break;
            case PayloadTransferUpdate.Status.SUCCESS:
                Payload payload = incomingPayloads.remove(update.getPayloadId());
                if (payload != null && payload.getType() == Payload.Type.FILE) {
                    // Retrieve the filename that was received in a bytes payload.
                    boolean wifi_established=transferUpdatesRSSI.size()>0;
                    for(int z=0; z < transferUpdatesBytes.size(); z++){
                        if(transferUpdatesRSSI.size() > z){
                            logStream.logTransfer(transferUpdatesTimes.get(z),transferUpdatesBytes.get(z),update.getTotalBytes(),transferUpdatesPositions.get(z),transferUpdatesFrequency.get(z),transferUpdatesLinkSpeed.get(z),transferUpdatesRSSI.get(z));
                        }else{
                            logStream.logTransfer(transferUpdatesTimes.get(z),transferUpdatesBytes.get(z),update.getTotalBytes(),transferUpdatesPositions.get(z));
                        }

                    }
                    int last=transferUpdatesBytes.size()-1;
                    if(!instantSpeed && transferUpdatesBytes.size() > 1){
                        double fileTransferSpeed=(transferUpdatesBytes.get(last) - transferUpdatesBytes.get(0))/1000000.0 / ((double)(transferUpdatesTimes.get(last)-transferUpdatesTimes.get(0))/1000000000.0);
                        UIsetText(WidgetEnum.instantSpeed,String.format( "%.2f", fileTransferSpeed )+" MB/s");
                    }
                    UIsetText(WidgetEnum.score,"Done");
                    UIsetProgress(WidgetEnum.transferBar,0);
                    resetTransferStats();
                    try {
                        askNextFile(RandomFileFactory.getSHA1(payload.asFile().asJavaFile()));
                    }catch (IOException | NoSuchAlgorithmException | NullPointerException e){
                        e.printStackTrace();
                    }

                }
                Payload payload2 = outgoingPayloads.remove(update.getPayloadId());
                if(payload2 != null && payload2.getType() == Payload.Type.FILE){
                    //scoreText.setText(R.string.done);
                    UIsetText(WidgetEnum.score,"Done");
                    UIsetProgress(WidgetEnum.transferBar,0);
                }
                break;
            case PayloadTransferUpdate.Status.FAILURE :
                Log.w(appName,"Failure to transfer payload "+update.toString());
                logStream.logError("PayloadTransferUpdate.Status.FAILURE "+update.toString());
                break;
        }
    }



    private void resetBench(){
        UIsetEnabled(WidgetEnum.start_benchmark,false);
        /*Interrupt connection */
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        if (!connectedEndpoints.isEmpty()){
            connectedEndpoints.clear();
        }
        connectionsClient.stopAllEndpoints();
        /* Ensure no payload remain */
        incomingPayloads = new SimpleArrayMap<>();
        outgoingPayloads = new SimpleArrayMap<>();
        resetTransferStats();
        /* Log file */
        logStream.flush();
        /* Clean cache */
        try {
            File[] files = context.getCacheDir().listFiles();
            for (File file : files) {
                file.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        nbFilesSelected=0;
        UIsetText(WidgetEnum.loopStatus,"");
        UIsetText(WidgetEnum.score,"");
        UIsetText(WidgetEnum.instantSpeed,"");
        UIsetText(WidgetEnum.status,"Idle");
    }

    private void resetTransferStats(){
        transferUpdatesBytes = new ArrayList<>();
        transferUpdatesPositions = new ArrayList<>();
        transferUpdatesTimes = new ArrayList<>();
        transferUpdatesRSSI = new ArrayList<>();
        transferUpdatesFrequency = new ArrayList<>();
        transferUpdatesLinkSpeed= new ArrayList<>();
    }

    private void startDiscovery() {
        // Todo: handle Discovery failure
        connectionsClient.startDiscovery(
                context.getPackageName(), endpointDiscoveryCallback, new DiscoveryOptions(STRATEGY));
    }

    private void startAdvertising() {
        // Todo: handle Advertising failure
        connectionsClient.startAdvertising(
                namePhone, context.getPackageName(), connectionLifecycleCallback, new AdvertisingOptions(STRATEGY));
    }

    private void findPhones() {
        /* Clean benchmark files */
        File folderNearby=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Nearby");
        if (folderNearby.exists()) {
            File[] ocatNearbyFiles = folderNearby.listFiles();
            for (File ocatNearbyFile : ocatNearbyFiles) {
                ocatNearbyFile.delete();
            }
        }

        if(!nearbyOn){
            if(multiMode){
                if(advertiser){
                    startAdvertising();
                    UIsetText(WidgetEnum.find_phones,"Stop Advertising");
                }else{
                    startDiscovery();
                    UIsetText(WidgetEnum.find_phones,"Stop Discovery");
                }
            }else {
                startAdvertising();
                startDiscovery();
                UIsetText(WidgetEnum.find_phones, "Stop Discovery");
            }
            logStream.logAdvertise(elapsedRealtimeNanos());
        }else{
            resetBench();
            UIsetText(WidgetEnum.find_phones,"Start Discovery");
            UIsetText(WidgetEnum.score,"");
            UIsetText(WidgetEnum.instantSpeed,"");
        }
        nearbyOn=!nearbyOn;
    }

    private void askNextFile(String hash){
        sendMessage("next/"+hash);
    }

    private void sendFile(){
        File Root = context.getCacheDir();
        if(Root.canWrite()) {
            File toSend = randomFilesToSend[currentFile];
            try {
                String filename=toSend.toString();
                filename=filename.substring(filename.lastIndexOf('/')+1);
                Payload filePayload = Payload.fromFile(toSend);
                String payloadFilenameMessage = Long.toString(toSend.length()) + ":" + filename;
                sendMessage(payloadFilenameMessage);
                connectionsClient.sendPayload(new ArrayList<>(connectedEndpoints.keySet()), filePayload);
                outgoingPayloads.put(filePayload.getId(),filePayload);
                UIsetText(WidgetEnum.score,"Sending "+filename);
            }catch (FileNotFoundException e){
                Log.w(appName,e.toString());
                logStream.logError(e.toString());
            }
        }
    }
    private void fireNextFile(){
        if(currentFile >= randomFilesToSend.length){
            sendMessage("end");
            vibrator.vibrate(patternEnd);
            r.play();
            UIsetText(WidgetEnum.distance,"");
            UIsetText(WidgetEnum.score,"Benchmark done");
            logStream.flush();
            UIsetEnabled(WidgetEnum.start_benchmark,true);
            randomFilesToSend=null;
            onGoingBenchmark=false;
            return;
        }
        sendFile();
        if((currentFile + 1) % nbFilesSelected == 0){
            currentLoop++;
        }
        currentFile++;
        UIsetText(WidgetEnum.loopStatus,"File "+currentFile+"/"+randomFilesToSend.length);
    }

    private void sendMessage(String message){
        try {
            Payload fileNamePayload = Payload.fromBytes(message.getBytes("UTF-8"));
            connectionsClient.sendPayload(new ArrayList<>(connectedEndpoints.keySet()), fileNamePayload);
            outgoingPayloads.put(fileNamePayload.getId(),fileNamePayload);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    public void run(){
        if (Looper.myLooper() == null){
            Looper.prepare();
        }
        workerHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case ThreadCommands.START_BENCHMARK:
                        msg.arg1 = lengthLoop;
                        sendFiles((List<Integer>) msg.obj);
                        break;
                    case ThreadCommands.SET_PARAMETERS_BENCHMARK:
                        setParameters((List<String>) msg.obj);
                        break;
                    case ThreadCommands.START_DISCOVERY:
                        namePhone = (String) msg.obj;
                        findPhones();
                        break;
                    case ThreadCommands.TRANSFER_UPDATE:
                        transferUpdate((PayloadTransferUpdate) msg.obj);
                        break;
                    case ThreadCommands.PAYLOAD_UPDATE:
                        payloadReceived((Payload) msg.obj);
                        break;
                    case ThreadCommands.RECONNECT:
                        if(multiMode){
                            if(advertiser){
                                startAdvertising();
                            }else{
                                startDiscovery();
                            }
                        }else {
                            startDiscovery();
                            startAdvertising();
                        }
                        logStream.logAdvertise(elapsedRealtimeNanos());
                        UIsetText(WidgetEnum.score, "Trying to reconnect");
                        break;
                }
                return true;
            }
        });

        Looper.loop();
    }
    private void sendFiles(List<Integer> listSize){
        ack=0;
        currentFile=0;
        currentLoop=0;
        UIsetText(WidgetEnum.score,"Generating files");
        UIsetEnabled(WidgetEnum.start_benchmark,false);
        int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        logStream.logBenchmark(false,comments,distance,lineOfSight,batLevel,connectedEndpoints.values().toString());
        lengthLoop = listSize.remove(0);

        nbFilesSelected = listSize.size();
        randomFilesToSend = new File[nbFilesSelected * lengthLoop];
        for (int j = 0; j < randomFilesToSend.length; j++) {
            if (reuseFile && j >= listSize.size()) {
                randomFilesToSend[j] = randomFilesToSend[j % listSize.size()];
            } else {
                try {
                    randomFilesToSend[j] = fileFactory.getRandomFile(listSize.get(j % listSize.size()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        //Todo: encoder facon input php (ca peut venir de l'exterieur) pour pas avoir de conflit
        onGoingBenchmark=true;
        sendMessage(comments+ "_" + distance+"_"+ namePhone);
        UIsetEnabled(WidgetEnum.start_benchmark,false);
    }

    private void UIsetText(int widget,String textToSet){
        Message m = new Message();
        m.what=ThreadCommands.SET_TEXT;
        m.arg1=widget;
        m.obj=textToSet;
        mHandler.sendMessage(m);
    }

    private void UIsetVisibility(int widget,int visibility){
        Message m = new Message();
        m.what=ThreadCommands.SET_VISIBILITY;
        m.arg1=widget;
        m.arg2=visibility;
        mHandler.sendMessage(m);
    }

    private void UIsetEnabled(int widget, boolean b){
        Message m = new Message();
        m.what=ThreadCommands.SET_ENABLED;
        m.arg1=widget;
        m.arg2=b ? 1 : 0;
        mHandler.sendMessage(m);
    }

    private void UIsetProgress(int widget, int progress){
        Message m = new Message();
        m.what=ThreadCommands.SET_PROGRESS;
        m.arg1=widget;
        m.arg2=progress;
        mHandler.sendMessage(m);
    }

    private void setParameters(List<String> params){
        /*
                0         1         2      3       4      5
            [namePhone,Comments,Distance,reuse,reconnect,los]
         */
        namePhone=params.get(0);
        if(params.size() > 1){
            comments=params.get(1);
            distance=params.get(2);
            reuseFile=Boolean.parseBoolean(params.get(3));
            reconnect=Boolean.parseBoolean(params.get(4));
            lineOfSight=params.get(5).equals("LoS");
        }
    }

}
