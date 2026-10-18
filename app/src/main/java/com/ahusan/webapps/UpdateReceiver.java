package com.ahusan.webapps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the result of the update install and shows Android's confirmation screen. */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        Toast.makeText(c, "Couldn't open the installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                // The app restarts with the new version.
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(c, "Update cancelled", Toast.LENGTH_SHORT).show();
                break;
            default:
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Update failed" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
        }
    }
}
