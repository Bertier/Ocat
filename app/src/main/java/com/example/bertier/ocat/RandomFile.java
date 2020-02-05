package com.example.bertier.ocat;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Formatter;

public class RandomFile {
    private File f;
    private final static String alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";


    public RandomFile(int sizeKB,File storageFolder) throws IOException{
        SecureRandom random = new SecureRandom();
        String randomappendix="";
        for(int lengthRandom=0;lengthRandom < 10; lengthRandom++) {
            randomappendix+=alphabet.charAt(random.nextInt(alphabet.length()));
        }
        File outputFile= new File(storageFolder,"random_"+sizeKB+"KB_"+randomappendix+".bin");
        OutputStream out = new FileOutputStream(outputFile);
        byte[] bytes = new byte[1000];
        for(int j=0; j < sizeKB;j++){
            random.nextBytes(bytes);
            out.write(bytes);
        }
        out.close();
        f=outputFile;
    }

    public File getFile(){
        return f;
    }

}
