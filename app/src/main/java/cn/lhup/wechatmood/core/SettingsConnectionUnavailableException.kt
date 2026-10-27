package cn.lhup.wechatmood.core

/** 旧界面引用的异常类型；当前报警走共享文件读写，不再需要设置桥接。 */
class SettingsConnectionUnavailableException(message: String) : IllegalStateException(message)
