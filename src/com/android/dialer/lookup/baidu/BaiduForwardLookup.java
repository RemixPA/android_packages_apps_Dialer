
package com.android.dialer.lookup.baidu;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.util.Log;

import com.android.dialer.calllog.ContactInfo;
import com.android.dialer.lookup.ContactBuilder;
import com.android.dialer.lookup.ForwardLookup;
import com.android.dialer.lookup.google.GoogleForwardLookup;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class BaiduForwardLookup extends ForwardLookup {
    private static final String TAG =
            GoogleForwardLookup.class.getSimpleName();

    private static final String LOOKUP_URL =
            "http://api.map.baidu.com/place/v2/search?ak=7397d486232d0a41a7ac893c157ad2c6&output=json&page_size=10&page_num=0&scope=2";
    private static final String CONVERT_GEO_URL = "http://api.map.baidu.com/geoconv/v1/?&ak=7397d486232d0a41a7ac893c157ad2c6";

    private static final String QUERY_COORDS = "coords";
    private static final String QUERY_FROM = "from";
    private static final String QUERY_TO = "to";
    private static final String QUERY_FILTER = "query";
    private static final String QUERY_LOCATION = "location";
    private static final String QUERY_RADIUS = "radius";
    private static final String RESULT_ARRAY_NAME = "results";
    private static final String RESULT_DETAIL_NAME = "detail_info";
    private static final String RESULT_NAME = "name";
    private static final String RESULT_ADDRESS = "address";
    private static final String RESULT_NUMBER = "telephone";
    private static final String RESULT_PHOTO_URI = "d";
    private static final String RESULT_WEBSITE = "detail_url";

    private static final int MIN_QUERY_LEN = 2;
    private static final int MAX_QUERY_LEN = 50;
    private static final int RADIUS = 3000;
    private static final String CHARSET = "UTF-8";

    @Override
    public ContactInfo[] lookup(Context context, String filter, Location lastLocation) {
        int length = filter.length();

        if (length >= MIN_QUERY_LEN) {
            if (length > MAX_QUERY_LEN) {
                filter = filter.substring(0, MAX_QUERY_LEN);
            }
            try {
                Uri.Builder builder = Uri.parse(LOOKUP_URL).buildUpon();

                // Query string
                builder = builder.appendQueryParameter(QUERY_FILTER, filter);

                // Location (latitude and longitude)
                double[] location = getConvertedGeo(lastLocation.getLatitude(),
                        lastLocation.getLongitude());
                double lat = location[0];
                double lng = location[1];
                builder = builder.appendQueryParameter(QUERY_LOCATION,
                        String.format("%f,%f", lat, lng));

                // Radius distance
                builder = builder.appendQueryParameter(QUERY_RADIUS,
                        Integer.toString(RADIUS));

                String httpResponse = httpGetRequest(
                        builder.build().toString());

                JSONObject results = new JSONObject(httpResponse);

                Log.v(TAG, "Results: " + results);

                return getEntries(results);
            } catch (IOException e) {
                Log.e(TAG, "Failed to execute query", e);
            } catch (JSONException e) {
                Log.e(TAG, "JSON error", e);
            }
        }
        return null;
    }

    private double[] getConvertedGeo(double lat, double lng) {
        Uri.Builder builder = Uri.parse(CONVERT_GEO_URL).buildUpon();
        builder.appendQueryParameter(QUERY_COORDS, lng + "," + lat)
                .appendQueryParameter(QUERY_FROM, "1").appendQueryParameter(QUERY_TO, "5");
        double[] result = new double[2];
        try {
            String json = httpGetRequest(builder.build().toString());
            JSONArray array = new JSONObject(json).getJSONArray("result");
            result[1] = array.getJSONObject(0).getDouble("x");
            result[0] = array.getJSONObject(0).getDouble("y");
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    private String httpGetRequest(String url) throws IOException {
        HttpClient client = new DefaultHttpClient();
        HttpGet request = new HttpGet(url.toString());

        HttpResponse response = client.execute(request);

        BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity()
                .getContent(), CHARSET));
        StringBuilder out = new StringBuilder();
        String line = "";
        while ((line = reader.readLine()) != null) {
            out.append(line);
        }
        return out.toString();
    }

    private ContactInfo[] getEntries(JSONObject results)
            throws JSONException {
        ArrayList<ContactInfo> details =
                new ArrayList<ContactInfo>();

        JSONArray result = results.getJSONArray(RESULT_ARRAY_NAME);

        for (int i = 0; i < result.length(); i++) {
            try {
                // Ensure those exists
                JSONObject obj = result.getJSONObject(i);
                String phoneNumber = obj.getString(RESULT_NUMBER);
                String displayName = obj.getString(RESULT_NAME);
                String address = obj.getString(RESULT_ADDRESS);

                JSONObject detail = obj.getJSONObject(RESULT_DETAIL_NAME);
                String profileUrl = detail.optString(RESULT_WEBSITE, null);
                String photoUri = obj.optString(RESULT_PHOTO_URI, null);

                ContactBuilder builder = new ContactBuilder(
                        ContactBuilder.FORWARD_LOOKUP, null, phoneNumber);

                ContactBuilder.Name n = new ContactBuilder.Name();
                n.displayName = displayName;
                builder.setName(n);

                ContactBuilder.PhoneNumber pn = new ContactBuilder.PhoneNumber();
                pn.number = phoneNumber;
                pn.type = Phone.TYPE_MAIN;
                builder.addPhoneNumber(pn);

                ContactBuilder.Address a = new ContactBuilder.Address();
                a.formattedAddress = address;
                a.type = StructuredPostal.TYPE_WORK;
                builder.addAddress(a);

                ContactBuilder.WebsiteUrl w = new ContactBuilder.WebsiteUrl();
                w.url = profileUrl;
                w.type = Website.TYPE_PROFILE;
                builder.addWebsite(w);

                if (photoUri != null) {
                    builder.setPhotoUri(photoUri);
                } else {
                    builder.setPhotoUri(ContactBuilder.PHOTO_URI_BUSINESS);
                }

                details.add(builder.build());
            } catch (JSONException e) {
                Log.e(TAG, "Skipping the suggestions at index " + i);
            }
        }

        if (details.size() > 0) {
            return details.toArray(new ContactInfo[details.size()]);
        } else {
            return null;
        }
    }

}
