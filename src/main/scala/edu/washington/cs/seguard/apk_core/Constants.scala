package edu.washington.cs.seguard.apk_core

import presto.android.Hierarchy
import soot.{Scene, SootClass, SootMethod}

object Constants {
  val hierarchy: Hierarchy = Hierarchy.v()

  private def backgroundApiKeywords = List(
    "org.apache.cordova.Callback",
    "org.apache.cordova.api.CordovaPlugin",
    "ChangeListener",
    "<java.lang.Runnable: void run()>"
  )

  private def backgroundHandlerNames = List(
    "onStart", "onCreate", "onPause", "onDestroy", "onActivityResult", "onResume", "onNewIntent", "onProgressUpdate"
  )

  def isBackgroundContextAPI(m: SootMethod): Boolean = {
    if (isBackgroundClass(m.getDeclaringClass) && backgroundHandlerNames.contains(m.getName)) {
      return true
    }
    if (backgroundApiKeywords.exists(k => m.getSignature.contains(k))) {
      return true
    }
    false
  }

  private val androidActivityClassNames = List(
    "android.app.Activity",
    "android.support.v7.app.AppCompatActivity",
    "androidx.appcompat.app.AppCompatActivity"
  )

  private val activityClasses = androidActivityClassNames.map(Scene.v().getSootClass(_))

  def isActivity(c: SootClass): Boolean = activityClasses.exists(activityClass => hierarchy.isSubclassOf(c, activityClass))

  private val serviceClass = Scene.v().getSootClass("android.app.Service")
  private val asyncTaskClass = Scene.v().getSootClass("android.os.AsyncTask")

  def isService(c: SootClass): Boolean = hierarchy.isSubclassOf(c, serviceClass)
  def isAsyncTask(c: SootClass): Boolean = hierarchy.isSubclassOf(c, asyncTaskClass)

  def isBackgroundClass(clazz: SootClass): Boolean =
    isActivity(clazz) || isService(clazz) || isAsyncTask(clazz)
}
