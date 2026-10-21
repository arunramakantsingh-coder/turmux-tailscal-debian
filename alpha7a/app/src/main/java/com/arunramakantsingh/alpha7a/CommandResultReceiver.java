package com.arunramakantsingh.alpha7a;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives the result of a command that was run inside Termux.
 *
 * <p>Termux answers a {@code com.termux.RUN_COMMAND} request by sending the pending intent that
 * the caller supplied. The result bundle is attached under the key {@code result} and holds
 * {@code stdout}, {@code stderr}, {@code exitCode}, {@code err} and {@code errmsg}.
 *
 * <p>The receiver is declared as not exported: the pending intent is created by this app, so the
 * platform delivers the broadcast with this app's identity and the receiver stays private.
 */
public class CommandResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null || !action.startsWith(TermuxRunner.ACTION_RESULT + ".")) return;
        String id = action.substring(action.lastIndexOf('.') + 1);

        Bundle bundle = intent.getBundleExtra(TermuxRunner.BUNDLE_KEY);
        if (bundle == null) {
            // Defensive: tolerate a differently keyed bundle.
            bundle = new Bundle();
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value instanceof Bundle) {
                        bundle = (Bundle) value;
                        break;
                    }
                }
            }
        }
        TermuxRunner.deliver(id, TermuxRunner.parse(bundle));
    }
}
