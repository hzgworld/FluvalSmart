package com.inledco.blemanager;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.SparseArray;

import com.ble.api.DataUtil;
import com.ble.api.EncodeUtil;
import com.ble.ble.BleCallBack;
import com.ble.ble.BleService;
import com.ble.ble.LeScanRecord;
import com.ble.ble.constants.BleRegConstants;
import com.ble.ble.constants.BleUUIDS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Created by liruya on 2017/4/25.
 */

public class BleManager extends BleCallBack implements ServiceConnection, BluetoothAdapter.LeScanCallback
{
    private static final String TAG = "BleManager";

    /**
     * request code for bluetooth enable
     */
    private static final int BLUETOOTH_REQUEST_ENABLE_CODE = 1;

    /**
     * request permisson code for coarse location
     */
    private static final int PERMISSON_REQUEST_COARSE_CODE = 1;

    /**
     * max lenght of data the bluetooth transfer
     */
    private static final int DATA_MAX_LENGTH = 17;

    /**
     * min interval between two receive data frames
     */
    private static final int DATA_FRAME_INTERVAL = 64;

    /**
     * min interval between two send data frames
     */
    private static final int DATA_SEND_INTERVAL = 32;

    /**
     * bluetooth scan period, default 15000ms
     */
    private int mScanPeriod = 12000;

    private boolean mScanning;

    private BleService mBleService;

//    private ScanCallback mScanCallback;

    /**
     * set of connected devices, string:device mac address,boolean:data valid or not
     */
    private Map< String, Boolean > mConnectedDevices;

    /**
     * receive data list
     */
    private ArrayList< Byte > mRcvBytes;
    private Runnable mScanRunnable;
    private Handler mHandler;
    private long msc;
    private BleStateListener mBleStateListener;
    private List< BleScanListener > mBleScanListeners;
    private List< BleCommunicateListener > mBleCommunicateListeners;

    private BleManager ()
    {
        mConnectedDevices = new HashMap<>();
        mRcvBytes = new ArrayList<>();
        mHandler = new Handler();
        mScanRunnable = new Runnable() {
            @Override
            public void run ()
            {
                stopScan();
            }
        };
        msc = System.currentTimeMillis();
    }

    public static BleManager getInstance ()
    {
        return BleHolder.INSTANCE;
    }

    public int getScanPeriod ()
    {
        return mScanPeriod;
    }

    public void setScanPeriod ( int scanPeriod )
    {
        mScanPeriod = scanPeriod;
    }

    /**
     * check does device support bluetooth/ble
     *
     * @param context
     * @return true:support/false:unsupport
     */
    public boolean checkBleSupported ( @NonNull Context context )
    {
        if ( context.getPackageManager()
                    .hasSystemFeature( PackageManager.FEATURE_BLUETOOTH_LE ) )
        {
            if ( BluetoothAdapter.getDefaultAdapter() != null )
            {
                return true;
            }
        }
        return false;
    }

    /**
     * bind service to activity
     *
     * @param context
     */
    public void bindService ( @NonNull Context context )
    {
        Intent intent = new Intent( context, BleService.class );
        context.bindService( intent, this, Context.BIND_AUTO_CREATE );
    }

    /**
     * unbind service from activity
     *
     * @param context
     */
    public void unbindService ( @NonNull Context context )
    {
        context.unbindService( this );
    }

    /**
     * check if bluetooth has been opened
     *
     * @return
     */
    public boolean isBluetoothEnabled ()
    {
        return BluetoothAdapter.getDefaultAdapter()
                               .isEnabled();
    }

    /**
     * open bluetooth without authorization
     *
     * @return true:open bluetooth success/false:open bluetooth failed
     */
    public boolean autoOpenBluetooth ()
    {
        return BluetoothAdapter.getDefaultAdapter()
                               .enable();
    }

    public void closeBluetooth ()
    {
        BluetoothAdapter.getDefaultAdapter().disable();
    }

    public void refresh( String mac )
    {
        mBleService.refresh( mac );
    }

    /**
     * request authorization to open bluetooth
     *
     * @param activity
     */
    public void requestBluetoothEnable ( Activity activity )
    {
        Intent intent = new Intent( BluetoothAdapter.ACTION_REQUEST_ENABLE );
        activity.startActivityForResult( intent, BLUETOOTH_REQUEST_ENABLE_CODE );
    }

    /**
     * get result of bluetooth authorization, call in method onActivityResult
     *
     * @param requestCode
     * @param resultCode
     */
    public void getResultForBluetoothEnable ( int requestCode, int resultCode )
    {
        if ( mBleStateListener != null )
        {
            if ( requestCode == BLUETOOTH_REQUEST_ENABLE_CODE && resultCode == Activity.RESULT_OK )
            {
                mBleStateListener.onBluetoothEnabled();
            }
            else
            {
                mBleStateListener.onBluetoothDenied();
            }
        }
    }

    /**
     * request coarse location permisson in activity
     *
     * @param activity
     */
    public void requestLocationPermison ( Activity activity )
    {
        //lower version does not need to running permisson
        //highr version check if user has granted app

        if ( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
             ContextCompat.checkSelfPermission( activity, Manifest.permission.ACCESS_COARSE_LOCATION ) == PackageManager.PERMISSION_GRANTED )
        {
            if ( mBleStateListener != null )
            {
                mBleStateListener.onCoarseLocationGranted();
            }
        }
        else
        {
            ActivityCompat.requestPermissions( activity, new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION }, PERMISSON_REQUEST_COARSE_CODE );
        }
    }

    /**
     * get result of coarse location permisson, call in onRequestPermissionsResult
     *
     * @param requestCode
     * @param permisson
     * @param grantCode
     */
    public void getResultForCoarseLocation ( int requestCode, String permisson, int grantCode )
    {
        if ( requestCode == PERMISSON_REQUEST_COARSE_CODE
             && permisson.equals( Manifest.permission.ACCESS_COARSE_LOCATION) )
        {
            if ( mBleStateListener != null )
            {
                if ( grantCode == PackageManager.PERMISSION_GRANTED )
                {
                    mBleStateListener.onCoarseLocationGranted();
                }
                else
                {
                    mBleStateListener.onCoarseLocationDenied();
                }
            }
        }
    }

    /**
     * start scan bluetooth device
     */
    public void startScan ()
    {
        if ( mBleScanListeners != null )
        {
            for ( BleScanListener listener : mBleScanListeners )
            {
                listener.onStartScan();
            }
        }

        BluetoothAdapter.getDefaultAdapter()
                        .startLeScan( this );
        mScanning = true;
        mHandler.postDelayed( mScanRunnable, mScanPeriod );
    }

    /**
     * stop scan
     */
    public void stopScan ()
    {
        if ( mScanning )
        {
            BluetoothAdapter.getDefaultAdapter()
                            .stopLeScan( this );
            mHandler.removeCallbacks( mScanRunnable );
            mScanning = false;
            if ( mBleScanListeners != null )
            {
                for ( BleScanListener listener : mBleScanListeners )
                {
                    listener.onStopScan();
                }
            }
        }
    }

    /**
     * connect device
     *
     * @param mac device mac address
     * @return true:success false:failure
     */
    public boolean connectDevice ( @NonNull final String mac )
    {
        if ( mBleService == null )
        {
            return false;
        }
        if ( isConnected( mac ) )
        {
            return true;
        }
        return mBleService.connect( mac, false );
    }

    /**
     * connect device
     *
     * @param mac device mac address
     * @return true:success false:failure
     */
    public boolean connectDevice ( @NonNull final String mac, int tick, int timeout, final BleCommunicateListener listener )
    {
        if ( mBleService == null )
        {
            return false;
        }
        if ( isConnected( mac ) )
        {
            return true;
        }
        new CountDownTimer( tick, timeout )
        {
            @Override
            public void onTick ( long millisUntilFinished )
            {
                if ( isConnected( mac ) )
                {
                    cancel();
                }
            }

            @Override
            public void onFinish ()
            {
                if ( !isConnected( mac ) && listener != null )
                {
                    BleManager.this.onConnectTimeout( mac );
                }
            }
        }.start();
        return mBleService.connect( mac, false );
    }

    /**
     * disconnect device
     *
     * @param mac device mac address
     */
    public void disconnectDevice ( @NonNull String mac )
    {
        if ( mBleService != null )
        {
            mBleService.disconnect( mac );
        }
    }

    /**
     * disconnect all device
     */
    public void disConnectAll ()
    {
        if ( mBleService != null && mConnectedDevices != null )
        {
            Set< String > adrs = new HashSet<>();
            adrs.addAll( mConnectedDevices.keySet() );
            for ( String mac : adrs )
            {
                mBleService.disconnect( mac );
            }
        }
    }

    /**
     * read manufactory data of broadcast data
     *
     * @param mac device mac address
     */
    public void readMfr ( @NonNull String mac )
    {
        mBleService.readReg( mac, BleRegConstants.REG_ADV_MFR_SPC );
    }

    /**
     * set remote device name
     *
     * @param mac device mac address
     * @param name device name
     */
    public void setSlaverName ( @NonNull String mac, @NonNull String name )
    {
        mBleService.setSlaverName( mac, name );
    }

    /**
     * send data to device
     *
     * @param mac device mac address
     * @param bytes data
     */
    public void sendBytes ( @NonNull final String mac, @NonNull final byte[] bytes )
    {
        if ( bytes.length <= DATA_MAX_LENGTH )
        {
            mBleService.send( mac, bytes, true );
            return;
        }
        new Thread( new Runnable() {
            @Override
            public void run ()
            {
                int idx = 0;
                LogUtil.d( TAG, "sendBytes: " + DataUtil.byteArrayToHex( bytes ) );
                while ( idx < bytes.length )
                {
                    int size = Math.min( bytes.length - idx, DATA_MAX_LENGTH );
                    byte[] bts = new byte[size];
                    for ( int i = 0; i < size; i++ )
                    {
                        bts[i] = bytes[idx];
                        idx++;
                    }
                    int count = 0;
                    while ( !mBleService.send( mac, bts, true ) )
                    {
                        LogUtil.d( TAG, "sendBytes: false" );
                        count++;
                        if ( count > 8 )
                        {
                            return;
                        }
                        try
                        {
                            Thread.sleep( 1 );
                        }
                        catch ( InterruptedException e )
                        {
                            e.printStackTrace();
                        }
                    }
                    LogUtil.d( TAG, "sendBytes: true" );
                    try
                    {
                        Thread.sleep( 8 );
                    }
                    catch ( InterruptedException e )
                    {
                        e.printStackTrace();
                    }
                }
            }
        } ).start();

    }

    public void clearReceiveBuffer()
    {
        if ( mRcvBytes != null )
        {
            mRcvBytes.clear();
        }
    }

    /**
     * check if device is connected
     *
     * @param mac device mac address
     * @return
     */
    public boolean isConnected ( @NonNull String mac )
    {
        return mConnectedDevices.containsKey( mac );
    }

    /**
     * check is valid to transfer data
     *
     * @param mac device mac address
     * @return
     */
    public boolean isDataValid ( @NonNull String mac )
    {
        if ( mConnectedDevices.containsKey( mac ) )
        {
            if ( mConnectedDevices.get( mac ) )
            {
                return true;
            }
        }
        return false;
    }

    /**
     * listen bluetooth state changed event,like initialized,enabled,disabled,coarse location permisson
     *
     * @param listener
     */
    public void setBleStateListener ( @NonNull BleStateListener listener )
    {
        mBleStateListener = listener;
    }

    /**
     * add bluetooth scan listen event, like start scan,stop scan,device scaned
     *
     * @param listener
     */
    public void addBleScanListener ( @NonNull BleScanListener listener )
    {
        if ( mBleScanListeners == null )
        {
            mBleScanListeners = new ArrayList<>();
        }
        mBleScanListeners.add( listener );
    }

    /**
     * remove bluetooth scan listener
     *
     * @param listener
     */
    public void removeBleScanListener ( @NonNull BleScanListener listener )
    {
        if ( mBleScanListeners != null && mBleScanListeners.contains( listener ) )
        {
            mBleScanListeners.remove( listener );
        }
    }

    /**
     * add Bluetooth transfer listener,like connect,disconnect,service discover,read reg,receive data
     *
     * @param listener
     */
    public void addBleCommunicateListener ( @NonNull BleCommunicateListener listener )
    {
        if ( mBleCommunicateListeners == null )
        {
            mBleCommunicateListeners = new ArrayList<>();
        }
        mBleCommunicateListeners.add( listener );
    }

    /**
     * remove Bluetooth transfer listener
     *
     * @param listener
     */
    public void removeBleCommunicateListener ( @NonNull BleCommunicateListener listener )
    {
        if ( mBleCommunicateListeners != null && mBleCommunicateListeners.contains( listener ) )
        {
            mBleCommunicateListeners.remove( listener );
        }
    }

    @Override
    public void onConnected ( String s )
    {
        super.onConnected( s );
        if ( !mConnectedDevices.containsKey( s ) )
        {
            mConnectedDevices.put( s, false );
        }
        LogUtil.d( TAG, "onConnected: " + s );
    }

    @Override
    public void onConnectTimeout ( String s )
    {
        super.onConnectTimeout( s );
        if ( mConnectedDevices.containsKey( s ) )
        {
            mConnectedDevices.remove( s );
        }
        if ( mBleCommunicateListeners != null )
        {
            for ( BleCommunicateListener listener : mBleCommunicateListeners )
            {
                listener.onDataInvalid( s );
            }
        }
        LogUtil.d( TAG, "onConnectTimeout: " + s );
    }

    @Override
    public void onConnectionError ( String s, int i, int i1 )
    {
        super.onConnectionError( s, i, i1 );
        if ( mConnectedDevices.containsKey( s ) )
        {
            mConnectedDevices.remove( s );
        }
        if ( mBleCommunicateListeners != null )
        {
            for ( BleCommunicateListener listener : mBleCommunicateListeners )
            {
                listener.onDataInvalid( s );
            }
        }
        LogUtil.d( TAG, "onConnectionError: " + s + "  \t" + i + "  \t" + i1 );
    }

    @Override
    public void onDisconnected ( String s )
    {
        super.onDisconnected( s );
        if ( mConnectedDevices.containsKey( s ) )
        {
            mConnectedDevices.remove( s );
        }
        if ( mBleCommunicateListeners != null )
        {
            for ( BleCommunicateListener listener : mBleCommunicateListeners )
            {
                listener.onDataInvalid( s );
            }
        }
        LogUtil.d( TAG, "onDisconnected: " + s );
    }

    @Override
    public void onServicesDiscovered ( final String s )
    {
        super.onServicesDiscovered( s );
        mHandler.postDelayed( new Runnable()
        {
            @Override
            public void run ()
            {
                mBleService.enableNotification( s );
            }
        }, 100 );
        mHandler.postDelayed( new Runnable()
        {
            @Override
            public void run ()
            {
                mConnectedDevices.put( s, true );
                if ( mBleCommunicateListeners != null )
                {
                    for ( BleCommunicateListener listener : mBleCommunicateListeners )
                    {
                        listener.onDataValid( s );
                    }
                }
            }
        }, 300 );
        LogUtil.d( TAG, "onServicesDiscovered: " + s );
    }

    @Override
    public void onServicesUndiscovered ( String s, int i )
    {
        super.onServicesUndiscovered( s, i );
        if ( mConnectedDevices.containsKey( s ) )
        {
            mConnectedDevices.put( s, false );
        }
        LogUtil.d( TAG, "onServicesUndiscovered: " + s + "  \t" + i );
    }

    @Override
    public void onCharacteristicRead ( String s, byte[] bytes, int i )
    {
        super.onCharacteristicRead( s, bytes, i );
        LogUtil.d( TAG, "onCharacteristicRead: " + s + "  \t" + DataUtil.byteArrayToHex( bytes ) + "  \t" + i );
    }

    @Override
    public void onRegRead ( String s, String s1, int i, int i1 )
    {
        super.onRegRead( s, s1, i, i1 );
        if ( i == BleRegConstants.REG_ADV_MFR_SPC )
        {
            if ( mBleCommunicateListeners != null )
            {
                for ( BleCommunicateListener listener : mBleCommunicateListeners )
                {
                    listener.onReadMfr( s, s1 );
                }
            }
        }
        LogUtil.d( TAG, "onRegRead: " + s + "  \t" + s1 + "  \t" + i + "  \t" + i1 );
    }

    @Override
    public void onCharacteristicChanged ( String s, byte[] bytes )
    {
        super.onCharacteristicChanged( s, bytes );
        long t = System.currentTimeMillis();
        if ( t - msc > DATA_FRAME_INTERVAL )
        {
            mRcvBytes.clear();
        }
        for ( byte b : bytes )
        {
            mRcvBytes.add( b );
        }
        msc = t;
        if ( mBleCommunicateListeners != null )
        {
            for ( BleCommunicateListener listener : mBleCommunicateListeners )
            {
                listener.onDataReceived( s, mRcvBytes );
            }
        }
        LogUtil.d( TAG, "onCharacteristicChanged: " + s + "  \t" + DataUtil.byteArrayToHex( bytes ) );
    }

    @Override
    public void onCharacteristicWrite ( String s, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i )
    {
        super.onCharacteristicWrite( s, bluetoothGattCharacteristic, i );
        LogUtil.d( TAG, "onCharacteristicWrite: " + s + "  \t" + i + "\t"
                        + DataUtil.byteArrayToHex( new EncodeUtil().decodeMessage( bluetoothGattCharacteristic.getValue() ) ) );
    }

    @Override
    public void onNotifyStateRead ( UUID uuid, UUID uuid1, boolean b )
    {
        super.onNotifyStateRead( uuid, uuid1, b );
        LogUtil.d( TAG, "onNotifyStateRead: " + b );
    }

    @Override
    public void onLeScan ( BluetoothDevice device, int rssi, byte[] scanRecord )
    {
        String mac = device.getAddress();
        String name = device.getName();
        LeScanRecord record = LeScanRecord.parseFromBytes( scanRecord );
        if ( record.getServiceUuids() == null || record.getServiceUuids().size() == 0 )
        {
            return;
        }
        ParcelUuid parcelUuid = (ParcelUuid) record.getServiceUuids().get( 0 );
        if ( !BleUUIDS.PRIMARY_SERVICE.equals( parcelUuid.getUuid() ) )
        {
            return;
        }
        SparseArray< byte[] > bytes = record.getManufacturerSpecificData();
        LogUtil.d( TAG, "onLeScan: Mac: " + mac + " rssi - " + rssi + "  \t" + record.toString() + "\r\n" + bytes.size() );
        byte[] rawData = null;
        if ( bytes != null && bytes.size() > 0 )
        {
            int id = bytes.keyAt( 0 );
            byte[] mfr = bytes.get( id );
            rawData = new byte[2 + mfr.length];
            rawData[0] = (byte) ( id & 0xFF );
            rawData[1] = (byte) ( ( id >> 8 ) & 0xFF );
            System.arraycopy( mfr, 0, rawData, 2, mfr.length );
        }
        if ( mBleScanListeners != null )
        {
            for ( BleScanListener listener : mBleScanListeners )
            {
                listener.onDeviceScanned( mac, name, rawData );
            }
        }
    }

    @Override
    public void onServiceConnected ( ComponentName name, IBinder service )
    {
        mBleService = ( (BleService.LocalBinder) service ).getService( this );
        mBleService.setDecode( true );
        //必须调用初始化方法
        mBleService.initialize();
        if ( mBleStateListener != null )
        {
            mBleStateListener.onBleInitialized();
        }
        LogUtil.d( TAG, "onServiceConnected: " );
    }

    @Override
    public void onServiceDisconnected ( ComponentName name )
    {
        mBleService = null;
        LogUtil.d( TAG, "onServiceDisconnected: " );
    }

    /**
     * Single instance holder
     */
    private static class BleHolder
    {
        private static final BleManager INSTANCE = new BleManager();
    }
}
