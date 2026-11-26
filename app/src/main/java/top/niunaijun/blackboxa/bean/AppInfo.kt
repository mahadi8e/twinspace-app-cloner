package top.niunaijun.blackboxa.bean

import android.graphics.drawable.Drawable


data class AppInfo(val name:String,val icon:Drawable?,val packageName:String,val isXpModule:Boolean,val isSystemApp:Boolean = false)