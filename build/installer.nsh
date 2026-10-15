!macro customInit
  ; 旧版卸载器会清空安装目录，升级前先暂存用户数据库。
  ReadRegStr $0 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
  ${If} $0 == ""
    ReadRegStr $0 HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation
  ${EndIf}
  ${If} $0 != ""
  ${AndIf} ${FileExists} "$0\settings.db"
    InitPluginsDir
    ClearErrors
    CopyFiles /SILENT "$0\settings.db" "$PLUGINSDIR\settings.db"
    ${If} ${Errors}
      MessageBox MB_ICONSTOP "无法备份原有 settings.db，安装已取消以保护数据。"
      Abort
    ${EndIf}
  ${EndIf}
!macroend

!macro customInstall
  ; 新版本可安装到不同目录，数据库跟随用户选定的安装目录。
  ${If} ${FileExists} "$PLUGINSDIR\settings.db"
  ${AndIfNot} ${FileExists} "$INSTDIR\settings.db"
    ClearErrors
    CopyFiles /SILENT "$PLUGINSDIR\settings.db" "$INSTDIR\settings.db"
    ${If} ${Errors}
      MessageBox MB_ICONSTOP "无法将原有 settings.db 恢复到安装目录，请检查目录写入权限。"
      Abort
    ${EndIf}
  ${EndIf}
!macroend
