package tungstwenty.xposed.masterkeydualfix;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class XposedModActivity extends Activity {

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		ViewGroup vg = (ViewGroup) getLayoutInflater().inflate(R.layout.main, null);
		makeTextViewsClickable(vg);
		setContentView(vg);

		boolean isActive = isActive();
		((TextView) findViewById(R.id.tvActive)).setVisibility(isActive ? View.VISIBLE : View.GONE);
		((TextView) findViewById(R.id.tvNotActive)).setVisibility(isActive ? View.GONE : View.VISIBLE);
	}

	private static void makeTextViewsClickable(ViewGroup vg) {
		for (int i = vg.getChildCount() - 1; i >= 0; i--) {
			View v = vg.getChildAt(i);
			if (v instanceof TextView)
				((TextView) v).setMovementMethod(LinkMovementMethod.getInstance());
			else if (v instanceof ViewGroup)
				makeTextViewsClickable((ViewGroup) v);
		}
	}

	public static boolean isActive() {
		return false;
	}
}
