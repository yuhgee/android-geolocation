package com.example.locationsample

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.locationsample.BuildConfig.*
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL


val TAG: String by lazy { MainActivity::class.simpleName ?: "Unknown" }
const val API_KEY = GOOGLE_API_KEY
//const val API_KEY = ""

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GeolocationScreen()
            }
        }
    }
}

@Composable
fun GeolocationScreen() {
    val context = LocalContext.current
    var resultText by remember { mutableStateOf("位置情報未取得") }

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { perms ->
                if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                    perms[Manifest.permission.ACCESS_WIFI_STATE] == true
                ) {
                    // OK
                } else {
                    resultText = "権限が拒否されました"
                }
            }
        )

    LaunchedEffect(Unit) {
        val hasPerm =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) ==
                    PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE
                )
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(resultText)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                resultText = "取得中..."
                getLastFusedLocation(context) { lat, lon, acc ->
                    resultText = if (lat != null && lon != null && acc != null) {
                        "FusedLocation 推定位置:\n 緯度=$lat,\n 経度=$lon,\n 制度=$acc"
                    } else {
                        "位置情報の取得に失敗"
                    }
                }
            }) {
                Text("GPSから位置取得")
            }
            Button(onClick = {
                resultText = "取得中..."
                getWifiLocation(context) { lat, lon, acc ->
                    resultText = if (lat != null && lon != null && acc != null) {
                        "Wifi 推定位置:\n 緯度=$lat,\n 経度=$lon,\n 制度=$acc"
                    } else {
                        "位置情報の取得に失敗"
                    }
                }
            }) {
                Text("Wi-Fiから位置取得")
            }
            Button(onClick = {
                resultText = "取得中..."
                getCellLocation(context)
                { lat, lon, acc ->
                    resultText = if (lat != null && lon != null && acc != null) {
                        "Cell 推定位置:\n 緯度=$lat,\n 経度=$lon,\n 制度=$acc"
                    } else {
                        "位置情報の取得に失敗"
                    }
                }

            }) {
                Text("Cellから位置取得")
            }
        }
    }
}

fun getLastFusedLocation(
    context: Context,
    onResult: (Double?, Double?, Double?) -> Unit
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    try {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    onResult(location.latitude, location.longitude, location.accuracy.toDouble())
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error: ${e.message}", e)
                onResult(null, null, null)
            }
    } catch (e: SecurityException) {
        Log.e(TAG, "Error: ${e.message}", e)
        onResult(null, null, null)
    }
}

fun createWifiRequestBody(context: Context): JSONObject? {
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val connectionInfo = wifiManager.connectionInfo
    val bssid = connectionInfo.bssid ?: return null
    val ssid = connectionInfo.ssid

    Log.d(TAG, "Wifi: Using BSSID=$bssid, SSID=$ssid")

    val jsonBody = JSONObject().apply {
        val wifiJson = JSONObject().apply {
            put("macAddress", bssid)
            // signalStrength や signalToNoiseRatio を入れる場合はここに追加
        }
        val wifiArray = JSONArray().put(wifiJson)
        put("wifiAccessPoints", wifiArray)
    }

    return jsonBody
}

private fun getWifiLocation(
    context: Context,
    onResult: (Double?, Double?, Double?) -> Unit) {
    val requestBody = createWifiRequestBody(context) ?: return onResult (null, null, null)
    getLocationFromGoogleApi(context, requestBody, onResult)
}

data class CellInfoData(
    val cellId: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val tac: Int? = null,
    val signalStrength: Int? = null
)

@SuppressLint("MissingPermission")
fun getCellInfoList(context: Context): List<CellInfoData> {
    val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    val cellInfoList = mutableListOf<CellInfoData>()
    val allCellInfo = telephonyManager.allCellInfo
    allCellInfo?.forEach { cell ->
        when (cell) {
            is CellInfoLte -> {
                val cellIdentity = cell.cellIdentity
                val cellSignal = cell.cellSignalStrength
                cellInfoList.add(
                    CellInfoData(
                        cellId = cellIdentity.ci,
                        lac = cellIdentity.tac,
                        mcc = cellIdentity.mcc,
                        mnc = cellIdentity.mnc,
                        tac = cellIdentity.tac,
                        signalStrength = cellSignal.dbm
                    )
                )
            }
            // ここに GSM, WCDMA, 5G NR などを追加可能
        }
    }

    return cellInfoList
}

fun getCellLocation(
    context: Context,
    onResult: (Double?, Double?, Double?) -> Unit
) {
    val cellInfoList = getCellInfoList(context)

    Log.d(TAG, "cell Using $cellInfoList")

    val requestBody = createCellRequestBody(cellInfoList) ?: return onResult(null, null, null)
    getLocationFromGoogleApi(context, requestBody, onResult)
}

fun createCellRequestBody(cellInfoList: List<CellInfoData>): JSONObject? {
    val cellArray = JSONArray()
    for (cell in cellInfoList) {
        val cellObj = JSONObject().apply {
            put("cellId", cell.cellId)
            put("locationAreaCode", cell.lac)
            put("mobileCountryCode", cell.mcc)
            put("mobileNetworkCode", cell.mnc)
            cell.tac?.let { put("tac", it) }
            cell.signalStrength?.let { put("signalStrength", it) }
        }
        cellArray.put(cellObj)
    }

    val jsonObject = JSONObject().apply {
        put("cellTowers", cellArray)
    }

    return jsonObject
}

fun getLocationFromGoogleApi(
    context: Context,
    requestBody: JSONObject,
    onResult: (Double?, Double?, Double?) -> Unit
) {
    Thread {
        try {
            val url = URL("https://www.googleapis.com/geolocation/v1/geolocate?key=$API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            val responseStream = try {
                conn.inputStream
            } catch (e: FileNotFoundException) {
                conn.errorStream
            }

            val response = responseStream.bufferedReader().readText()
            Log.d(TAG, "GeoAPI Response: $response")

            try {
                val jsonResponse = JSONObject(response)
                val location = jsonResponse.getJSONObject("location")
                val lat = location.getDouble("lat")
                val lon = location.getDouble("lng")
                val acc = jsonResponse.getDouble("accuracy")
                (context as ComponentActivity).runOnUiThread {
                    onResult(lat, lon, acc)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                (context as ComponentActivity).runOnUiThread {
                    onResult(null, null, null)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            (context as ComponentActivity).runOnUiThread {
                onResult(null, null, null)
            }
        }
    }.start()
}
