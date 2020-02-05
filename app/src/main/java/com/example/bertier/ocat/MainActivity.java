package com.example.bertier.ocat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String[] REQUIRED_PERMISSIONS =
            new String[] {
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
    Context context = this;
    /* Widgets variables */
    private TextView statusText;
    private TextView scoreText;
    private TextView instantSpeedText;
    private TextView loopStatus;
    private TextView ApiText;
    private EditText commentsText;
    private EditText distanceText;
    private EditText loopsText;
    private TextView nameText;
    private Button startBenchmarkButton;
    private Button findPhonesButton;
    private ProgressBar transferProgressBar;
    private LinearLayout scrollLayout;
    private Spinner spinner;
    /*Strings*/
    private final String appName="Ocat";
    private String namePhone;
    private String topology="";
    private final int[] sizeFiles = {20000,10000,5000,2000,1000,100,10,1};
    /* Booleans */
    private boolean reconnect=true;
    private boolean reuseFile=true;
    private boolean advertiser;
    private boolean multiSend;
    /*Data Structures*/
    private List<Integer> defaultFiles = Arrays.asList(1);
    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;
    /* Special classes */
    Handler mHandlerThread;
    WifiManager wifimanager;
    WifiP2pManager mManager;
    WifiP2pManager.Channel mChannel;
    /* Worker threads */
    NearbyWorker nearby;
    WiFiP2PWorker p2p;

    int selectedAPI=APIs.WiFiP2P;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar=findViewById(R.id.my_toolbar);
        setSupportActionBar(toolbar);

        PreferenceManager.setDefaultValues(this,R.xml.preference,false);
        /* Match widgets to variables */
        ApiText = findViewById(R.id.APIchosen);
        statusText=findViewById(R.id.status);
        instantSpeedText=findViewById(R.id.instantSpeed);
        scoreText=findViewById(R.id.score);
        loopStatus=findViewById(R.id.loopStatus);
        startBenchmarkButton=findViewById(R.id.start_benchmark);
        findPhonesButton=findViewById(R.id.find_phones);
        transferProgressBar=findViewById(R.id.transferBar);
        nameText = findViewById(R.id.namePhone);
        commentsText = findViewById(R.id.commentsField);
        distanceText = findViewById(R.id.distanceField);
        loopsText = findViewById(R.id.numberLoop);
        scrollLayout = findViewById(R.id.scrollLayout);
        for(int i = 0; i < sizeFiles.length; i++){
            CheckBox check = new CheckBox(this);
            check.setText(sizeFiles[i]+"");
            if(defaultFiles.contains(i)){
                check.setChecked(true);
            }
            scrollLayout.addView(check);
        }
        spinner = findViewById(R.id.spinnerLos);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.lineOfSight, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        loadPreferences();

        nameText.setText(namePhone);
        if(selectedAPI == APIs.NEARBY){
            if(multiSend){
                if(advertiser){
                    ApiText.setText("Nearby-"+topology+" sender: ");
                }else{
                    ApiText.setText("Nearby-"+topology+" receiver: ");
                }
            }else{
                ApiText.setText("Nearby: ");
            }
        }else if(selectedAPI == APIs.WiFiP2P){
            ApiText.setText("WiFiP2P: ");
        }
        mHandlerThread = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch(msg.what){
                    case ThreadCommands.SET_ENABLED:
                        if (msg.arg1 == WidgetEnum.find_phones){
                            findPhonesButton.setEnabled(msg.arg2==1);
                        }else if(msg.arg1 == WidgetEnum.start_benchmark){
                            startBenchmarkButton.setEnabled(msg.arg2==1);
                        }
                        break;
                    case ThreadCommands.SET_TEXT:
                        if(msg.arg1 == WidgetEnum.find_phones){
                            findPhonesButton.setText((String)msg.obj);
                        }else if(msg.arg1 == WidgetEnum.status){
                            statusText.setText((String) msg.obj);
                        }else if(msg.arg1 == WidgetEnum.score){
                            scoreText.setText((String) msg.obj);
                        }else if(msg.arg1 == WidgetEnum.distance){
                            distanceText.setText((String)msg.obj);
                        }else if(msg.arg1 == WidgetEnum.comments){
                            commentsText.setText((String) msg.obj);
                        }else if(msg.arg1 == WidgetEnum.instantSpeed){
                            instantSpeedText.setText((String)msg.obj);
                        }else if(msg.arg1 == WidgetEnum.loopStatus){
                            loopStatus.setText((String) msg.obj);
                        } else if (msg.arg1 == WidgetEnum.start_benchmark) {
                            startBenchmarkButton.setText((String)msg.obj);
                        }
                        break;
                    case ThreadCommands.SET_PROGRESS:
                        if(msg.arg1 == WidgetEnum.transferBar){
                            transferProgressBar.setProgress(msg.arg2);
                        }
                        break;
                    case ThreadCommands.SET_VISIBILITY:
                        if(msg.arg1 == WidgetEnum.start_benchmark){
                            startBenchmarkButton.setVisibility(msg.arg2);
                        }
                        break;
                }
                return true;
            }
        });

        if(selectedAPI== APIs.NEARBY){
            nearby = new NearbyWorker(this,mHandlerThread,multiSend,advertiser,topology);
            nearby.start();
        }else if(selectedAPI == APIs.WiFiP2P) {
            wifimanager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            mChannel = mManager.initialize(this, getMainLooper(), null);
            p2p = new WiFiP2PWorker(context,wifimanager,mManager,mChannel,mHandlerThread);
            p2p.start();
        }

    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this,SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
    protected void onStart() {
        super.onStart();

        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
        }
    }

    private static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void selectAll(View view){
        selectFiles(1);
    }
    public void deselectAll(View view){
        selectFiles(0);
    }

    public void selectDefault(View view){
        selectFiles(2);
    }
    private void selectFiles(int choice){
        int defaultCursorPos = 0;
        for (int i = 0; i < scrollLayout.getChildCount(); i++) {
            View v = scrollLayout.getChildAt(i);
            if (v instanceof CheckBox) {
                if(choice < 2){
                    ((CheckBox)v).setChecked(choice==1);
                }else{
                    if(defaultFiles.get(defaultCursorPos) == i){
                        ((CheckBox)v).setChecked(true);
                        if(defaultCursorPos+1 != defaultFiles.size()){
                            defaultCursorPos++;
                        }
                    }else{
                        ((CheckBox)v).setChecked(false);
                    }
                }

            }
        }
    }

    public void findPhones(View view) {
        Message m = new Message();
        m.what=ThreadCommands.START_DISCOVERY;
        m.obj=namePhone;
        if(selectedAPI == APIs.NEARBY){
            nearby.workerHandler.sendMessage(m);
        }else if(selectedAPI == APIs.WiFiP2P){
            p2p.workerHandler.sendMessage(m);
        }
    }

    public void sendFiles(View view){
        if(distanceText.getText().toString().isEmpty()){
            Context context = getApplicationContext();
            CharSequence text = "Distance is not set.";
            int duration = Toast.LENGTH_SHORT;
            Toast toast = Toast.makeText(context, text, duration);
            toast.show();
            return;
        }
        Message m = new Message();
        m.what = ThreadCommands.SET_PARAMETERS_BENCHMARK;
        List<String>params=new ArrayList<>();
        params.add(namePhone);
        params.add(commentsText.getText().toString());
        params.add(distanceText.getText().toString());
        params.add(String.valueOf(reuseFile));
        params.add(String.valueOf(reconnect));
        params.add(spinner.getSelectedItem().toString());
        m.obj=params;
        if(selectedAPI == APIs.NEARBY){
            nearby.workerHandler.sendMessage(m);
        }else if(selectedAPI == APIs.WiFiP2P){
            p2p.workerHandler.sendMessage(m);
        }

        int lengthLoop = Integer.parseInt(loopsText.getText().toString());
        List<Integer> sizeFiles = new ArrayList<>();
        sizeFiles.add(lengthLoop);
        for (int i = 0; i < scrollLayout.getChildCount(); i++) {
            View v = scrollLayout.getChildAt(i);
            if (v instanceof CheckBox && ((CheckBox) v).isChecked()) {
                sizeFiles.add(Integer.parseInt(((CheckBox) v).getText().toString()));
            }
        }
        m = new Message();
        m.what = ThreadCommands.START_BENCHMARK;
        m.obj = sizeFiles;
        if(selectedAPI == APIs.NEARBY){
            nearby.workerHandler.sendMessage(m);
        }else if(selectedAPI == APIs.WiFiP2P){
            p2p.workerHandler.sendMessage(m);
        }
    }
    private void loadPreferences(){
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String name = prefs.getString("namePhone", null);//"No name defined" is the default value.
        if(name != null) {
            namePhone = name;
            nameText.setText(namePhone);
        }
        int loops= Integer.parseInt(prefs.getString("numberLoop","5"));
        loopsText.setText(""+loops);
        //int distance= Integer.parseInt(prefs.getString("distance","10"));
        reuseFile=prefs.getBoolean("reuse",true);
        reconnect=prefs.getBoolean("reconnect",false);
        //multiSend=prefs.getBoolean("multiSend",false);
        multiSend=true; //Todo: remove the variable accordingly. For now, let it be true all the time.
        topology=prefs.getString("list_topologies","Cluster");
        advertiser=prefs.getBoolean("advertiserMode",false);

        String prefList = prefs.getString("APIList", "1");
        selectedAPI=Integer.parseInt(prefList);
        //distanceText.setText(""+distance);

        statusText.setText("Idle");
        startBenchmarkButton.setEnabled(false);
    }
    @Override
    protected void onResume(){
        super.onResume();
        //TODO: Quick and dirty solution, but a preference change listener ought to be done
        int previousAPI=selectedAPI;
        loadPreferences();
        if(previousAPI != selectedAPI){
            Intent intent = getIntent();
            this.finish();
            startActivity(intent);
            return;
        }
        if(selectedAPI == APIs.WiFiP2P){
            registerReceiver(p2p.mReceiver,p2p.intentFilter);
        }
    }


    @Override
    protected void onPause(){
        super.onPause();
        if(selectedAPI == APIs.WiFiP2P){
            unregisterReceiver(p2p.mReceiver);
        }
    }
    @Override
    protected void onStop(){
        super.onStop();
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        namePhone=nameText.getText().toString();
        Log.i(appName,namePhone);
        editor.putString("namePhone", namePhone);
        editor.putString("numberLoop", loopsText.getText().toString());
        //editor.putString("distance", distanceText.getText().toString());
        if(selectedAPI == APIs.NEARBY){
            editor.putString("APIList","1");
        }else if(selectedAPI == APIs.WiFiP2P){
            editor.putString("APIList","2");
        }

        editor.apply();
        editor.commit();
        if(selectedAPI == APIs.WiFiP2P){
            p2p.cleanup();
        }
    }


}
