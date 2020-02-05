package com.example.bertier.ocat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.content.Context.BATTERY_SERVICE;
import static android.os.SystemClock.elapsedRealtime;
import static android.os.SystemClock.elapsedRealtimeNanos;

public class WiFiP2PWorker extends Thread {
    private static final int thisAPI = APIs.WiFiP2P;
    private Handler mHandler;
    private WifiManager wifimanager;
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;
    public BroadcastReceiver mReceiver;
    public Handler workerHandler;
    private final String locationProvider = LocationManager.GPS_PROVIDER;

    private List<WifiP2pDevice> peers= new ArrayList<>();
    private String[] deviceNames;
    private WifiP2pDevice[] deviceArray;
    public IntentFilter intentFilter;
    private int lengthLoop;
    private String appName="Ocat";

    private String currentTargetName;
    private Socket socketData;
    private Socket socketSignal;
    private ServerSocket serverSocketData;
    private ServerSocket serverSocketSignal;

    private int currentFile=0;
    private int currentLoop=0;
    private int nbFilesSelected;
    private Context context;
    private Location currentEstimateLocation;
    private LocationManager locationManager;
    private LogWriter logStream;
    private BatteryManager bm;
    private RandomFileFactory fileFactory;
    private File[] randomFilesToSend;
    private boolean reuseFile=true;
    private boolean discoveryOn=false;
    private boolean lineOfSight=false;
    private boolean instantSpeed=false;
    /*Strings*/
    private String namePhone;
    private String comments="";
    private String distance="";
    private int sizeChunk=128*1000;

    VibrationEffect patternConnect=VibrationEffect.createWaveform(new long[]{0, 50, 1000},-1);
    VibrationEffect patternDisconnect=VibrationEffect.createWaveform(new long[]{0, 150, 100,150},-1);
    VibrationEffect patternEnd=VibrationEffect.createOneShot(2000,255);
    private Vibrator vibrator;
    private Ringtone r;
    ListeningThread listening;

    public WiFiP2PWorker(Context c,WifiManager wifi, WifiP2pManager manager,WifiP2pManager.Channel channel, Handler h){
        context=c;
        wifimanager=wifi;
        mManager=manager;
        mChannel=channel;
        mReceiver=new WiFiDirectBroadcastReceiver(mManager,mChannel,this);
        intentFilter=new IntentFilter();

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        r = RingtoneManager.getRingtone(c.getApplicationContext(), notification);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHandler=h;
        try {
            logStream=new LogWriter(c);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            locationManager= (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            locationManager.requestLocationUpdates(locationProvider, 0, 0, locationListener);
        }catch (SecurityException e){
            //TODO: Treat when user doesn't grant location access
            Log.w(appName,"Can't find location: "+e.toString());
        }
        bm = (BatteryManager)context.getSystemService(BATTERY_SERVICE);
        fileFactory = new RandomFileFactory(context.getCacheDir());

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

    public WifiP2pManager.PeerListListener listener=new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            long time=elapsedRealtimeNanos();
            if(!peerList.getDeviceList().equals(peers)){
                Log.i(appName,"New devices");
                peers.clear();
                peers.addAll(peerList.getDeviceList());
                deviceNames=new String[peers.size()];
                deviceArray=new WifiP2pDevice[peers.size()];
                int index=0;
                for(WifiP2pDevice device: peerList.getDeviceList()){
                    deviceNames[index]=device.deviceName;
                    deviceArray[index]=device;
                    index++;
                }
                UIsetText(WidgetEnum.score,Arrays.toString(deviceNames));

            }
            if (peers.size() == 0) {
                Log.d(appName, "No devices found");
                UIsetText(WidgetEnum.status,"disconnected WiFiDirect");
                UIsetEnabled(WidgetEnum.start_benchmark,false);
                //TODO: If there was a connection, this means we lost it. Gracefully disconnect and cleanup.
                logStream.logDisconnection("");
                return;
            }else {
                logStream.logDiscoverPeer(time,Arrays.toString(deviceNames));
                UIsetText(WidgetEnum.start_benchmark,"Connect");
                UIsetEnabled(WidgetEnum.start_benchmark,true);
            }
        }
    };

    public WifiP2pManager.ConnectionInfoListener connectionInfoListener=new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            long time=elapsedRealtimeNanos();
            final InetAddress groupOwnerAddress=wifiP2pInfo.groupOwnerAddress;
            if(wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner){
                logStream.logConnectionEstablished(thisAPI,time,currentTargetName,"host");
                UIsetText(WidgetEnum.status,"WiFiDirect connection successful as host");
                if(discoveryOn)
                    workerHandler.obtainMessage(ThreadCommands.ACCEPT_P2P_CONNECTION).sendToTarget();
            }else if(wifiP2pInfo.groupFormed){
                logStream.logConnectionEstablished(thisAPI,time,currentTargetName,"client");
                UIsetText(WidgetEnum.status,"WiFiDirect connection successful as client");
                if(discoveryOn)
                    workerHandler.obtainMessage(ThreadCommands.CREATE_P2P_CONNECTION,groupOwnerAddress).sendToTarget();
            }
        }
    };
    public void run(){
        Looper.prepare();
        workerHandler = new Handler(new Handler.Callback() {
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case ThreadCommands.START_BENCHMARK:
                        msg.arg1 = lengthLoop;
                        sendFiles((List<Integer>) msg.obj);
                        break;
                    case ThreadCommands.START_DISCOVERY:
                    case ThreadCommands.STOP_DISCOVERY:
                        findPhones();
                        break;
                    case ThreadCommands.ACCEPT_P2P_CONNECTION:
                        incoming_connection();
                        break;
                    case ThreadCommands.CREATE_P2P_CONNECTION:
                        outgoing_connection((InetAddress) msg.obj);
                        break;
                    case ThreadCommands.SET_PARAMETERS_BENCHMARK:
                        setParameters((List<String>) msg.obj);
                        break;

                }
                return true;
            }
        });

        Looper.loop();
    }

    private void findPhones(){
        if(!wifimanager.isWifiEnabled()){
            wifimanager.setWifiEnabled(true);
        }
        if(!discoveryOn){
            mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    UIsetText(WidgetEnum.status,"Scanning for peers");
                }

                @Override
                public void onFailure(int reasonCode) {
                    UIsetText(WidgetEnum.status,"Failed to scan for peers (code"+reasonCode+")");
                }
            });
            long time=elapsedRealtimeNanos();
            logStream.logAdvertise(time);
            UIsetText(WidgetEnum.find_phones,"Stop Discovery");
        }else{
           disconnect_TCP();

           disconnect_WiFiDirect();
           peers.clear();
           listening=null;
            try {
                if(socketSignal!=null)
                    socketSignal.close();
                if(socketData!=null)
                    socketData.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
           UIsetText(WidgetEnum.score,"");
           UIsetText(WidgetEnum.find_phones,"Start Discovery");
        }
        discoveryOn = !discoveryOn;

    }

    private void incoming_connection(){
        try{
            serverSocketSignal = new ServerSocket(8887);
            serverSocketData = new ServerSocket(8888);
            socketSignal=serverSocketSignal.accept();
            socketData = serverSocketData.accept();
        } catch (IOException e) {
            e.printStackTrace();
        }
        logStream.logConnectionTCP(elapsedRealtimeNanos());
        listening = new ListeningThread();
        listening.start();
        vibrator.vibrate(patternConnect);
        UIsetEnabled(WidgetEnum.start_benchmark,true);
        UIsetText(WidgetEnum.status,"TCP connection successful as host");
        UIsetText(WidgetEnum.start_benchmark,"Start sending files");
    }

    private void outgoing_connection(InetAddress hostAddress){
        String hostAdd=hostAddress.getHostAddress();
        do {
            try {
                Thread.sleep(200);
                socketSignal=new Socket();
                socketData=new Socket();
                socketSignal.connect(new InetSocketAddress(hostAdd, 8887), 500);
                socketData.connect(new InetSocketAddress(hostAdd, 8888), 500);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }while(!socketSignal.isConnected());
        logStream.logConnectionTCP(elapsedRealtimeNanos());
        listening = new ListeningThread();
        listening.start();
        vibrator.vibrate(patternConnect);
        UIsetEnabled(WidgetEnum.start_benchmark,true);
        UIsetText(WidgetEnum.status,"TCP connection successful as client");
        UIsetText(WidgetEnum.start_benchmark,"Start sending files");
    }

    public void connect(){
        // Picking the first device found on the network.
        WifiP2pDevice device = peers.get(0);
        currentTargetName=deviceNames[0];

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        Log.i(appName,"connecting to "+config.deviceAddress);
        mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // WiFiDirectBroadcastReceiver notifies us. Ignore for now.
            }

            @Override
            public void onFailure(int reason) {
                Log.w(appName,"Failed to initiate connection.");
            }
        });
        long time=elapsedRealtimeNanos();
        logStream.logConnectionStart(thisAPI,time,currentTargetName,"");
    }

    public void cleanup(){
        disconnect_TCP();
        disconnect_WiFiDirect();
        logStream.close();
        try {
            File[] files = context.getCacheDir().listFiles();
            for (File file : files) {
                file.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void disconnect_TCP(){
        try {
            if(socketSignal != null) {
                socketSignal.close();
                socketData.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    public void disconnect_WiFiDirect() {
        mManager.stopPeerDiscovery(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(appName,"stopPeerDiscovery onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Log.i(appName,"stopPeerDiscovery onFailure -"+reason);
            }
        });
        if (mManager != null && mChannel != null) {
            mManager.requestGroupInfo(mChannel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null && mManager != null && mChannel != null
                            && group.isGroupOwner()) {
                        mManager.removeGroup(mChannel, new WifiP2pManager.ActionListener() {

                            @Override
                            public void onSuccess() {
                                Log.d(appName, "removeGroup onSuccess -");
                            }

                            @Override
                            public void onFailure(int reason) {
                                Log.d(appName, "removeGroup onFailure -" + reason);
                            }
                        });
                    }
                }
            });
        }
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
            lineOfSight=params.get(5).equals("LoS");

        }
    }

    private void sendFiles(List<Integer> listSize){
        if(listening==null){
            connect();
            UIsetEnabled(WidgetEnum.start_benchmark,false);
            return;
        }
        currentFile=0;
        currentLoop=0;
        UIsetText(WidgetEnum.score,"Generating files");
        UIsetEnabled(WidgetEnum.start_benchmark,false);
        int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        logStream.logBenchmark(false,comments,distance,lineOfSight,batLevel,currentTargetName);
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
        sendMessage(comments+ "_" + distance + "_" + namePhone);
        UIsetEnabled(WidgetEnum.start_benchmark,false);
        UIsetText(WidgetEnum.status,"connected to "+currentTargetName);
    }

    private void sendMessage(String message){
        try {
            byte[] msg=message.getBytes("UTF-8");
            socketSignal.getOutputStream().write(msg,0,msg.length);
            socketSignal.getOutputStream().flush();
        }catch (IOException e){
            e.printStackTrace();
            logStream.logError(e.toString());
        }
    }


    private void sendFile(){
        File Root = context.getCacheDir();
        if(Root.canWrite()) {
            File toSend = randomFilesToSend[currentFile];
            String filename=toSend.toString();
            filename=filename.substring(filename.lastIndexOf('/')+1);
            long sizeFile = toSend.length();
            String payloadFilenameMessage = Long.toString(sizeFile) + ":" + filename;
            sendMessage(payloadFilenameMessage);
            UIsetText(WidgetEnum.score,"Sending "+filename);
            try {
                InputStream fStream = new FileInputStream(toSend);
                OutputStream oStream = socketData.getOutputStream();
                byte[] buffer = new byte[sizeChunk];
                int count,totalSent=0;
                Double progress;
                while ((count = fStream.read(buffer)) > 0) {
                    oStream.write(buffer, 0, count);
                    totalSent+=count;
                    progress=100.0 * totalSent / (double) sizeFile;
                    UIsetProgress(WidgetEnum.transferBar,progress.intValue());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            UIsetText(WidgetEnum.score,"Done");
        }
    }
    private void fireNextFile(){
        if(currentFile >= randomFilesToSend.length){
            sendMessage("end");
            vibrator.vibrate(patternEnd);
            r.play();
            UIsetText(WidgetEnum.distance,"");
            UIsetText(WidgetEnum.score,"Benchmark done");
            UIsetEnabled(WidgetEnum.start_benchmark,true);
            randomFilesToSend=null;
            return;
        }
        sendFile();
        if((currentFile + 1) % nbFilesSelected == 0){
            currentLoop++;
        }
        currentFile++;
        UIsetText(WidgetEnum.loopStatus,"File "+currentFile+"/"+randomFilesToSend.length);
    }
    private void askNextFile(String hash){
        sendMessage(("next/"+hash));
    }

    private void UIsetText(int widget,String textToSet){
        Message m = new Message();
        m.what=ThreadCommands.SET_TEXT;
        m.arg1=widget;
        m.obj=textToSet;
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

    private class ListeningThread extends Thread{
        private List<Long> transferUpdatesTimes = new ArrayList<>();
        private List<Integer> transferUpdatesBytes = new ArrayList<>();
        private List<Location> transferUpdatesPositions = new ArrayList<>();
        private boolean sendingSuccess=true;
        private int numberRetry=5;
        private int currentRetry=0;

        @Override
        public void run() {
            while (!socketSignal.isClosed()) {
                try {
                    InputStream signals = socketSignal.getInputStream();
                    InputStream dataflow = socketData.getInputStream();
                    byte[] buffer = new byte[1000];
                    int size;
                    File Root = context.getCacheDir();
                    while ((size = signals.read(buffer)) > 0) {
                        String message = new String(buffer, "UTF-8").substring(0,size);
                        Log.i(appName,"Message received: "+message);
                        int colonIndex = message.indexOf(':');
                        int underscoreIndex = message.indexOf('_');
                        int slashIndex = message.indexOf('/');
                        if (colonIndex > -1) {
                            String filename = message.substring(colonIndex + 1);
                            File outputFile = new File(Root,filename);
                            int sizeFile= Integer.parseInt(message.substring(0,colonIndex));
                            UIsetText(WidgetEnum.score, "Receiving " + filename);
                            logStream.logIncomingFile(sizeFile,filename);
                            UIsetEnabled(WidgetEnum.start_benchmark, false);
                            int receivedSize=0,sizeCurrent;
                            Double progress;
                            byte[] dataBuffer=new byte[sizeChunk];
                            OutputStream fStream = new FileOutputStream(outputFile);
                            if (!outputFile.exists()) {
                                outputFile.createNewFile();
                            }
                            Long update_time;
                            while(receivedSize<sizeFile){
                                sizeCurrent=dataflow.read(dataBuffer);
                                update_time=elapsedRealtimeNanos();
                                transferUpdatesTimes.add(update_time);
                                receivedSize+=sizeCurrent;
                                transferUpdatesBytes.add(receivedSize);
                                fStream.write(dataBuffer,0,sizeCurrent);
                                int s= transferUpdatesTimes.size()-1;
                                if(instantSpeed && s > 1) {
                                    long bytesReceived, timeDiff;
                                    double instantTransferSpeed;
                                    bytesReceived=transferUpdatesBytes.get(s)-transferUpdatesBytes.get(s-1);
                                    timeDiff=transferUpdatesTimes.get(s)-transferUpdatesTimes.get(s-1);
                                    double fileTransferSpeed=(bytesReceived/1000000.0) / ((double)(timeDiff)/1000.0);
                                    UIsetText(WidgetEnum.instantSpeed,String.format( "%.2f", fileTransferSpeed )+" MB/s");
                                }
                                try {
                                    currentEstimateLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                    transferUpdatesPositions.add(currentEstimateLocation);
                                }catch (SecurityException e){
                                    transferUpdatesPositions.add(null);
                                }
                                progress=100.0 * receivedSize / (double) sizeFile;
                                UIsetProgress(WidgetEnum.transferBar,progress.intValue());
                            }
                            fStream.close();
                            UIsetProgress(WidgetEnum.transferBar,100);
                            int i;
                            for(i=0; i < transferUpdatesBytes.size(); i++){
                                logStream.logTransfer(transferUpdatesTimes.get(i),transferUpdatesBytes.get(i),sizeFile,transferUpdatesPositions.get(i));
                            }
                            int last=transferUpdatesBytes.size()-1;
                            if(!instantSpeed && transferUpdatesBytes.size() > 1){
                                double fileTransferSpeed=((transferUpdatesBytes.get(last) - transferUpdatesBytes.get(0))/1000000.0) / ((double)(transferUpdatesTimes.get(last)-transferUpdatesTimes.get(0))/1000000000.0);
                                UIsetText(WidgetEnum.instantSpeed,String.format( "%.2f", fileTransferSpeed )+" MB/s");
                            }
                            UIsetText(WidgetEnum.score,"Done");
                            transferUpdatesBytes = new ArrayList<>();
                            transferUpdatesPositions = new ArrayList<>();
                            transferUpdatesTimes = new ArrayList<>();
                            String hash=RandomFileFactory.getSHA1(outputFile);
                            askNextFile(hash);
                        } else if (underscoreIndex > -1) {
                            int secondSlashIndex=message.indexOf('_',underscoreIndex+1);
                            String comment = message.substring(0,underscoreIndex);
                            String distance=message.substring(underscoreIndex+1,secondSlashIndex);
                            currentTargetName=message.substring(secondSlashIndex+1);
                            int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                            logStream.logBenchmark(true,comment,distance,lineOfSight,batLevel,currentTargetName);
                            UIsetText(WidgetEnum.distance, distance);
                            UIsetText(WidgetEnum.comments, comment);
                            UIsetText(WidgetEnum.status, "connected to "+currentTargetName);
                            askNextFile("");
                        } else if (slashIndex > -1) {
                            String remoteHash = message.substring(slashIndex + 1);
                            if (!remoteHash.isEmpty()) {
                                try {
                                    String hash = RandomFileFactory.getSHA1(randomFilesToSend[currentFile - 1]);
                                    Log.i(appName, "local:" + hash + " remote:" + remoteHash);
                                    if (!hash.equals(remoteHash)) {
                                        UIsetText(WidgetEnum.score, "Hash do not match. Abort.");
                                        //sendMessage("end");
                                        logStream.logError(" hashLocal "+hash+" and hashRemote "+remoteHash+" not a match");
                                        //logStream.logEndBenchmark();
                                    }
                                } catch (NoSuchAlgorithmException | IOException e) {
                                    e.printStackTrace();
                                    logStream.logError(e.toString());
                                }
                            }
                            if(sendingSuccess){
                                fireNextFile();
                            }else{
                                currentRetry++;
                                sendingSuccess=true;
                                if(currentRetry < numberRetry){
                                    sendFile();
                                }else{
                                    currentRetry=0;
                                    fireNextFile();
                                }
                            }

                        } else if (message.equals("end")) {
                            logStream.logEndBenchmark();
                            vibrator.vibrate(patternEnd);
                            r.play();
                            UIsetText(WidgetEnum.score, "Benchmark done.");
                            UIsetProgress(WidgetEnum.transferBar, 0);
                            UIsetEnabled(WidgetEnum.start_benchmark, true);
                        }
                    }
                }catch (SocketException e1){
                    logStream.logError(e1.toString());
                }
                catch (IOException | NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
            }
            Log.i(appName,"Exiting listening thread");
        }
    }
}
