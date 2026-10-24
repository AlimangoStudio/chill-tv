package app.alimango.chilltv.showcase;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the remote control press Back in any app (Netflix, Settings...). It listens to no events and reads no screen
 * content; it only performs the global Back action when the remote asks for it.
 */
public class RemoteAccessibility extends AccessibilityService {
    private static volatile RemoteAccessibility instance;

    static boolean back() {
        RemoteAccessibility s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(android.content.Intent intent) { instance = null; return super.onUnbind(intent); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }
}
