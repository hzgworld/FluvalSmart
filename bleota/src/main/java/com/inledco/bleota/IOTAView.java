package com.inledco.bleota;

public interface IOTAView
{
    void showDeviceConnected();
    void showDeviceDisconnected();
    void showDeviceVersion(String version);
    void showRemoteVersion(String version);
    void showUpgradeConfirmDialog(String msg);
    void showMessage(String msg);
    void showUpgradeProgress(String msg);
    void showRepowerDialog();
}
