package ru.xphone.booster;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки или обновления — снова следить за играми. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.enabled(c) && MainActivity.permissionsOk(c)) BoostService.start(c);
    }
}
