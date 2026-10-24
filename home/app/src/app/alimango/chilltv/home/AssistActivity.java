package app.alimango.chilltv.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The remote's mic (Assistant) button, now that this app holds the Assistant role: bring up the home screen
 * with the assistant's orb listening. The assistant runs its own commands and passes anything else to Google search.
 */
public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("listen", true));
        finish();
        overridePendingTransition(0, 0);
    }
}
