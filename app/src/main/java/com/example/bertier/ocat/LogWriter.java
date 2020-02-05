package com.example.bertier.ocat;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import static java.lang.System.currentTimeMillis;

public class LogWriter {
    private static Context context;
    private static BufferedWriter out;
    public static class Tags {
        public static final String ERROR="error";
        public static final String BENCHMARK="run";
        public static final String APPSTART="ocat";
        public static final String TRANSFER="transfer";
        public static final String CONNECTIONSTARTNEARBY="connect nearby start";
        public static final String CONNECTIONESTABLISHEDNEARBY="connect nearby established";
        public static final String CONNECTIONSTARTWIFIDIRECT="connect wifidirect start";
        public static final String CONNECTIONESTABLISHEDWIFIDIRECT="connect wifidirect established";
        public static final String CONNECTIONESTABLISHEDTCP="connect tcp established";
        public static final String STARTDETECTION="advertise";
        public static final String PEERDISCOVERY="discovery";
        public static final String DISCONNECTION="disconnect";
        public static final String INCOMINGFILE="receiving";
        public static final String COMMENTS="comments";
        public static final String ENDBENCHMARK="end";

        private Tags() {}
    }
    public LogWriter(Context c) throws IOException{
        this.context=c;
        createFile();

    }

    private void createFile() throws IOException {
        boolean append=true;
        File Root = context.getFilesDir();
        if(Root.canWrite()){
            File  LogFile = new File(Root, "LogOcat.txt");
            FileWriter LogWriter = new FileWriter(LogFile, append);
            out = new BufferedWriter(LogWriter);
            Date date = new Date();
            out.write("\n");
            out.write(Tags.APPSTART+" " + String.valueOf(date.toString()+"\n"));
        }
    }

    public void writeToFile(String message) {
        try {
            out.write(message + "\n");
            //out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logAdvertise(long elapsedTimeNanos){
        writeToFile(Tags.STARTDETECTION+" "+currentTimeMillis()+" "+elapsedTimeNanos);
    }

    public void logDiscoverPeer(long elapsedTimeNanos,String PeerID){
        writeToFile(Tags.PEERDISCOVERY+" "+elapsedTimeNanos);
    }

    public void logConnectionStart(int API,long elapsedTimeNanos, String PeerID, String additionalInfo){
        if(API == APIs.NEARBY){
            writeToFile(Tags.CONNECTIONSTARTNEARBY+" "+elapsedTimeNanos+" "+PeerID+" "+additionalInfo);
        }else if(API == APIs.WiFiP2P){
            writeToFile(Tags.CONNECTIONSTARTWIFIDIRECT+" "+elapsedTimeNanos+" "+additionalInfo);
        }
    }
    public void logConnectionEstablished(int API,long elapsedTimeNanos,String PeerID, String additionalInfo){
        if(API == APIs.NEARBY){
            writeToFile(Tags.CONNECTIONESTABLISHEDNEARBY+" "+elapsedTimeNanos+" "+PeerID+" "+additionalInfo);
        }else if(API == APIs.WiFiP2P){
            writeToFile(Tags.CONNECTIONESTABLISHEDWIFIDIRECT+" "+elapsedTimeNanos+" "+additionalInfo);
        }
    }

    public void logConnectionTCP(long elapsedTimeNanos){
        writeToFile(Tags.CONNECTIONESTABLISHEDTCP+" "+elapsedTimeNanos);
    }
    public void logBenchmark(Boolean receiver,String comment,String distance,Boolean los,int battery,String targetName){
        writeToFile(" ");
        writeToFile(Tags.BENCHMARK+" "+receiver+" "+distance+" "+los+" "+battery+" "+targetName+" "+currentTimeMillis ());
        writeToFile(Tags.COMMENTS+" "+comment);
    }

    public void logIncomingFile(long fileSize,String name){
        writeToFile(Tags.INCOMINGFILE+" "+fileSize+" "+name);
    }
    public void logTransfer(long elapsedTimeNano, long transferUpdate, long totalSize, Location c){
        if(c != null) {
            writeToFile(Tags.TRANSFER + " " + elapsedTimeNano + " " + transferUpdate + " " + totalSize + " " + c.getLatitude() + " " + c.getLongitude());
        }else{
            writeToFile(Tags.TRANSFER + " " + elapsedTimeNano + " " + transferUpdate + " " + totalSize + " null null");
        }
    }

    public void logTransfer(long elapsedTimeNano, long transferUpdate, long totalSize, Location c,int frequency, int linkspeed, int rssi){
        String towrite=Tags.TRANSFER + " " + elapsedTimeNano + " " + transferUpdate + " " + totalSize + " ";
        if(c != null) {
            writeToFile(towrite+c.getLatitude() + " " + c.getLongitude()+" "+frequency+" "+linkspeed+" "+rssi);
        }else{
            writeToFile(towrite+"null null "+frequency+" "+linkspeed+" "+rssi);
        }
    }

    public void logEndBenchmark(){
        writeToFile(Tags.ENDBENCHMARK+" "+currentTimeMillis());
    }
    public void logDisconnection(String peerID){
        writeToFile(Tags.DISCONNECTION+" "+currentTimeMillis()+" "+peerID);
    }
    public void logError(String additionalInfo){
        writeToFile(Tags.ERROR+" "+currentTimeMillis()+" "+additionalInfo);
    }


    public void close(){
        try {
            out.close();
        } catch (IOException e) {
            Log.e("Ocat",e.toString());
        }
    }

    public void flush(){
        close();
        boolean append=true;
        File Root = context.getFilesDir();
        if(Root.canWrite()) {
            try {
                File LogFile = new File(Root, "LogOcat.txt");
                FileWriter LogWriter = new FileWriter(LogFile, append);
                out = new BufferedWriter(LogWriter);
            }catch (IOException e){

            }
        }
    }

}

