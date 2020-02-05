package com.example.bertier.ocat;

import android.app.Activity;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private static final String preferenceFile="Ocat_pref";
    public static final String KEY_NAME="namePhone";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content,new SettingsFragment()).commit();


    }
}
