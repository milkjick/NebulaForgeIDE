package com.nebulaforge.spike;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class MainActivity extends Activity {
  @Override public void onCreate(Bundle state) { super.onCreate(state); TextView v=new TextView(this); v.setText("NebulaForge Stage 0 Spike\\nGradle Tooling API bridge validation project"); setContentView(v); }
}
