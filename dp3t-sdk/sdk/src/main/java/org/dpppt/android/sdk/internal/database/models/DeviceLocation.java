package org.dpppt.android.sdk.internal.database.models;

import android.location.Location;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.github.davidmoten.geo.GeoHash;

public class DeviceLocation {
    private int id;
    private long interval = 10*1000;
    private long time;
    private double latitude;
    private double longitude;
    private String hashes;
    private int hashLength = 8;
    private double[][] radii = {{0.0, 0.0},{0.0001, 0.0},{0.00007, 0.00007},{0.0, 0.0001},{-0.00007, 0.00007},{-0.0001, 0.0},{-0.00007, -0.00007},{0.0, -0.0001},{0.00007, -0.00007}};
    public DeviceLocation(Location location){
        this.time = location.getTime();
        this.latitude = location.getLatitude();
        this.longitude = location.getLongitude();
    }
    public DeviceLocation(long time, double latitude, double longitude,long interval) {
        this.time = time;
        this.latitude = latitude;
        this.longitude = longitude;
        this.interval = interval;
    }
    public DeviceLocation(long time, double latitude, double longitude) {
        this.time = time;
        this.latitude = latitude;
        this.longitude = longitude;
    }
    public DeviceLocation(long time, double latitude, double longitude,String hashes) {
        this.time = time;
        this.latitude = latitude;
        this.longitude = longitude;
        this.hashes = hashes;
    }
    public ArrayList<String> getLocationHashes(){
        return getGeoHashes();
    }
    private ArrayList<String> getGeoHashes(){
        Set<String> geoHashesSet = new HashSet<>();
        for (double[] radius : radii) {
            double lat = radius[0] + latitude;
            double lon = radius[1] + longitude;
            geoHashesSet.add(GeoHash.encodeHash(lat, lon, hashLength));

        }
        ArrayList<String> geoHashes = new ArrayList<>(geoHashesSet);
        return geoHashes;
    }
    public long[] getTimeWindow(){
        long early = (time - interval/2)/interval*interval;
        long late = (time + interval/2)/interval*interval;
        long timeWindow[] = {early,late};
        return timeWindow;
    }
    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getHashes() {
        return hashes;
    }

    public void setHashes(String hashes) {
        this.hashes = hashes;
    }
    @NonNull
    public String toString(){
        return "Time: "+time+"\tLatitude: "+latitude+"\tLongitude: "+longitude+"\tHashes:"+hashes;

    }
    public int getHashLength() {
        return hashLength;
    }

    public void setHashLength(int hashLength) {
        this.hashLength = hashLength;
    }
}
