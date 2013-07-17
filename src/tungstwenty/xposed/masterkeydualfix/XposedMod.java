package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookZygoteInit, IXposedHookLoadPackage {

	private static final String THIS_PACKAGE = XposedMod.class.getPackage().getName();

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		findAndHookMethod(ZipFile.class, "readCentralDir", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				try {
					ZipFilePatch.readCentralDir((ZipFile) param.thisObject);
					param.setResult(null);
				} catch (Exception ex) {
					param.setThrowable(ex);
				}
			}
		});
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (THIS_PACKAGE.equals(lpparam.packageName)) {
			findAndHookMethod(XposedModActivity.class.getName(), lpparam.classLoader, "isActive",
			    XC_MethodReplacement.returnConstant(true));
		}
	}
}
