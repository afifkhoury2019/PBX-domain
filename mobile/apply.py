#!/usr/bin/env python3
"""
AFIF Phone build overlay.

Turns a checkout of linphone-android into "AFIF Phone":
  * AFIF branding (name, package, colours, icons, splash, logos)
  * the Tailscale engine + its sign-in/status screens as the :tailscale module ("Secure network")
  * AFIF-PBX presets (domain voip.powerchain.uk:5160, UDP, keep-alive, no Linphone cloud services)

Usage: apply.py --linphone DIR --tailscale DIR   (version: gradle -PafifVersionName=X -PafifVersionCode=N)
Every edit is checked: if an upstream file changed shape, the script stops with a clear message.
"""
import argparse
import os
import re
import shutil
import sys

ap = argparse.ArgumentParser()
ap.add_argument("--linphone", required=True)
ap.add_argument("--tailscale", required=True)
ap.add_argument("--res", default=None, help="branding PNGs (default: mobile/res, made by make_res.py)")
args = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
args.res = args.res or os.path.join(HERE, "res")
pins = dict(l.strip().split("=", 1) for l in open(os.path.join(HERE, "pins.env")) if "=" in l and not l.startswith("#"))
APP_ID, APP_NAME = pins["APP_ID"], pins["APP_NAME"]
L = os.path.abspath(args.linphone)
T = os.path.abspath(args.tailscale)
APP = os.path.join(L, "app")
MAIN = os.path.join(APP, "src", "main")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def edit(path, old, new, count=1, regex=False):
    s = read(path)
    if regex:
        s2, n = re.subn(old, new, s, count=0 if count == 0 else count, flags=re.S)
    else:
        n = s.count(old)
        s2 = s.replace(old, new) if count == 0 else s.replace(old, new, count)
    if n == 0:
        sys.exit(f"apply.py: pattern not found in {os.path.relpath(path, L)}:\n  {old[:160]!r}")
    write(path, s2)
    print(f"  edited {os.path.relpath(path, L) if path.startswith(L) else path} ({min(n, count) if count else n}x)")


print("== Gradle project")
kotlin_ver = re.search(r'^kotlin\s*=\s*"([^"]+)"', read(os.path.join(L, "gradle", "libs.versions.toml")), re.M).group(1)
print("  Kotlin", kotlin_ver)
edit(os.path.join(L, "settings.gradle.kts"), 'include(":app")', 'include(":app")\ninclude(":tailscale")')
edit(os.path.join(L, "settings.gradle.kts"), 'rootProject.name = "Linphone"', 'rootProject.name = "AFIF-Phone"')
edit(os.path.join(L, "settings.gradle.kts"),
     "    repositories {\n        google()\n        mavenCentral()\n\n        maven {",
     "    versionCatalogs {\n        create(\"tslibs\") { from(files(\"tailscale/libs.versions.toml\")) }\n    }\n\n"
     "    repositories {\n        google()\n        mavenCentral()\n        // libtailscale.aar built by the CI\n"
     "        maven { url = uri(File(rootDir, \"localrepo\")) }\n\n        maven {")
edit(os.path.join(L, "build.gradle.kts"), "    alias(libs.plugins.crashlytics) apply false",
     "    alias(libs.plugins.crashlytics) apply false\n"
     f'    id("org.jetbrains.kotlin.plugin.compose") version "{kotlin_ver}" apply false\n'
     f'    id("org.jetbrains.kotlin.plugin.serialization") version "{kotlin_ver}" apply false')

print("== :tailscale module (from tailscale-android)")
TS = os.path.join(L, "tailscale")
shutil.rmtree(TS, ignore_errors=True)
shutil.copytree(os.path.join(T, "android", "src", "main"), os.path.join(TS, "src", "main"))
shutil.copy(os.path.join(T, "android", "proguard-rules.pro"), os.path.join(TS, "proguard-rules.pro"))
shutil.copy(os.path.join(T, "android", "gradle", "libs.versions.toml"), os.path.join(TS, "libs.versions.toml"))
shutil.copy(os.path.join(HERE, "tailscale-module.gradle.kts"), os.path.join(TS, "build.gradle.kts"))
shutil.copy(os.path.join(HERE, "afif-proguard.pro"), os.path.join(TS, "afif-proguard.pro"))
shutil.copy(os.path.join(T, "LICENSE"), os.path.join(TS, "LICENSE-tailscale"))
TSJ = os.path.join(TS, "src", "main", "java", "com", "tailscale", "ipn")
# Tailscale's launcher icons (mipmap-anydpi-v26 beat Linphone's mipmap-anydpi): AFIF Phone keeps its own icon
for d in os.listdir(os.path.join(TS, "src", "main", "res")):
    if d.startswith("mipmap"):
        shutil.rmtree(os.path.join(TS, "src", "main", "res", d))
        print("  removed tailscale res/" + d)
# AFIF Phone's Application class extends Tailscale's
edit(os.path.join(TSJ, "App.kt"), "\nclass App : UninitializedApp()", "\nopen class App : UninitializedApp()")
# The secure network carries AFIF Phone's own traffic only: every other app on the phone keeps its normal network.
edit(os.path.join(TSJ, "IPNService.kt"), r"    val mdmAllowed =.*?(?=    return VPNServiceBuilder\(b\))",
     "    // AFIF Phone: only AFIF Phone itself uses the secure network\n"
     "    TSLog.d(TAG, \"Secure network limited to $packageName\")\n"
     "    allowApp(b, packageName)\n\n", regex=True)
man = os.path.join(TS, "src", "main", "AndroidManifest.xml")
# the app (AFIF Phone) owns <application> attributes; keep only Tailscale's components
edit(man, r"<application\b[^>]*>", "<application>", regex=True)
edit(man, '            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n\n'
          '                <category android:name="android.intent.category.LAUNCHER" />\n'
          '                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n            </intent-filter>\n', "")
edit(man, r'\s*<activity\s+android:name="ShareActivity".*?</activity>', "", regex=True)
edit(man, '            android:name="MainActivity"', '            android:name="MainActivity"\n'
          '            android:theme="@style/Theme.App.SplashScreen"')
edit(man, '            android:label="@string/app_name"\n            android:launchMode="singleTask"\n            android:windowSoftInputMode="adjustResize">',
     '            android:label="Secure network"\n            android:launchMode="singleTask"\n            android:windowSoftInputMode="adjustResize">')

print("== app module: identity, signing, dependency on :tailscale")
gb = os.path.join(APP, "build.gradle.kts")
edit(gb, 'val packageName = "org.linphone"', f'val packageName = "{APP_ID}"')
edit(gb, r'versionCode = \d+[^\n]*', 'versionCode = providers.gradleProperty("afifVersionCode").getOrElse("1").toInt()', regex=True)
edit(gb, 'println("Computed git version: $gitVersion")',
     'gitVersion = providers.gradleProperty("afifVersionName").getOrElse("1.0")\nprintln("Computed git version: $gitVersion")')
edit(gb, 'versionName = "6.3.0-alpha"', 'versionName = providers.gradleProperty("afifVersionName").getOrElse("1.0")')
edit(gb, 'output.outputFileName = "linphone-android-${variant.buildType.name}-$gitVersion.apk"',
     'output.outputFileName = "AFIF-Phone-${variant.buildType.name}.apk"')
edit(gb, 'abiFilters += listOf("armeabi-v7a", "arm64-v8a")',
     'abiFilters += providers.gradleProperty("afifAbis").getOrElse("armeabi-v7a,arm64-v8a").split(",")')
edit(gb, "dependencies {", 'dependencies {\n    implementation(project(":tailscale"))\n    implementation("com.squareup.okhttp3:okhttp:4.12.0")', count=1)
gs = os.path.join(APP, "google-services.json")
if os.path.exists(gs):
    os.remove(gs)  # Linphone cloud config only works for the official app
    print("  removed google-services.json (no Linphone push/crash reporting)")

print("== Application class")
edit(os.path.join(MAIN, "java", "org", "linphone", "LinphoneApplication.kt"),
     "class LinphoneApplication : Application(), SingletonImageLoader.Factory",
     "class LinphoneApplication : com.tailscale.ipn.App(), SingletonImageLoader.Factory")

print("== Name, colours")
n = 0
for d in sorted(os.listdir(os.path.join(MAIN, "res"))):
    p = os.path.join(MAIN, "res", d, "strings.xml")
    if d.startswith("values") and os.path.exists(p):
        s = read(p)
        s2 = re.sub(r'<!ENTITY appName "[^"]*">', f'<!ENTITY appName "{APP_NAME}">', s)
        s2 = s2.replace(">Linphone<", f">{APP_NAME}<")
        if s2 != s:
            write(p, s2)
            n += 1
print(f"  app name set in {n} language files")
colors = os.path.join(MAIN, "res", "values", "colors.xml")
edit(colors, '<color name="orange_main_100">#FFEACB</color>', '<color name="orange_main_100">#DCE8FF</color>')
edit(colors, '<color name="orange_main_500">#FF5E00</color>', '<color name="orange_main_500">#2563EB</color>')

print("== Icons, splash, logos")
for root, _, files in os.walk(args.res):
    for f in files:
        src = os.path.join(root, f)
        dst = os.path.join(MAIN, "res", os.path.relpath(src, args.res))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(src, dst)
for root, _, files in os.walk(os.path.join(HERE, "overlay")):
    for f in files:
        src = os.path.join(root, f)
        dst = os.path.join(MAIN, os.path.relpath(src, os.path.join(HERE, "overlay")))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(src, dst)
        print("  overlay", os.path.relpath(dst, L))
for icon in ("ic_launcher.xml", "ic_launcher_round.xml"):
    edit(os.path.join(MAIN, "res", "mipmap-anydpi", icon), '@color/orange_main_500', '@color/afif_launcher_background')

print("== Presets for AFIF-PBX")
rc = os.path.join(MAIN, "assets", "linphonerc_default")
edit(rc, 'contact="Linphone Android" <sip:linphone.android@unknown-host>', f'contact="{APP_NAME}" <sip:afif.phone@unknown-host>')
edit(rc, "[app]\n", "[app]\nkeep_service_alive=1\n")
# AFIF-PBX speaks plain RTP: never insist on SRTP (the phone would hang up the moment a call is answered)
edit(rc, "media_encryption=none\n", "media_encryption=none\nmedia_encryption_mandatory=0\n")
edit(rc, "version_check_url_root=https://download.linphone.org/releases\n", "version_check_url_root=\n")
edit(rc, "[ui]\ncontacts_filter=sip.linphone.org\n",
     "[ui]\ncontacts_filter=\n"
     "assistant_third_party_sip_account_domain=\n"
     "assistant_third_party_sip_account_transport=udp\n"
     "assistant_hide_create_account=1\n"
     "assistant_disable_qr_code=1\n"
     "assistant_go_directly_to_third_party_sip_account_login=1\n"
     "disable_meetings_feature=1\n"
     # SMS through AFIF-PBX appear as messages (SIP MESSAGE); screenshots allowed; colour can be changed
     "enable_secure_mode=0\n"
     "change_main_color_allowed=1\n"
     "theme_main_color=orange\n")
# no Linphone servers: no file uploads to linphone.org from the message screen, no log uploads
edit(rc, "log_collection_upload_server_url=https://files.linphone.org/http-file-transfer-server/hft.php\n", "log_collection_upload_server_url=\n")
edit(rc, "file_transfer_server_url=https://files.linphone.org/http-file-transfer-server/hft.php\n", "file_transfer_server_url=\n")
tp = os.path.join(MAIN, "assets", "assistant_third_party_default_values")
edit(tp, '<entry name="stun_server" overwrite="true">stun.linphone.org</entry>', '<entry name="stun_server" overwrite="true"></entry>')
edit(tp, '<entry name="protocols" overwrite="true">stun,ice</entry>', '<entry name="protocols" overwrite="true"></entry>')
edit(tp, '<entry name="media_encryption">srtp</entry>', '<entry name="media_encryption">none</entry>')

print("== Messages, background, colours")
en = os.path.join(MAIN, "res", "values", "strings.xml")
edit(en, '<string name="bottom_navigation_conversations_label">Conversations</string>', '<string name="bottom_navigation_conversations_label">Messages</string>')
edit(en, '<string name="settings_conversations_title">Conversations</string>', '<string name="settings_conversations_title">Messages</string>')
edit(en, '<string name="generic_address_picker_conversations_list_title">Conversations</string>', '<string name="generic_address_picker_conversations_list_title">Messages</string>')
edit(en, '<string name="orange">Orange</string>', '<string name="orange">AFIF Blue</string>')
edit(en, '<string name="settings_advanced_keep_alive_service_title">Keep app alive using Service</string>',
     '<string name="settings_advanced_keep_alive_service_title">Run in background (calls and messages with the screen off)</string>')
edit(os.path.join(MAIN, "AndroidManifest.xml"), '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />',
     '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n'
     '    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />')
svm = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "settings", "viewmodel", "SettingsViewModel.kt")
edit(svm, "            corePreferences.keepServiceAlive = newValue\n",
     "            corePreferences.keepServiceAlive = newValue\n"
     "            if (newValue) coreContext.context.getSharedPreferences(\"afif_phone\", 0).edit().remove(\"battery_asked_v1\").apply()\n")

print("== Secure network option in the SIP account's advanced settings")
CHECK = ('                <androidx.appcompat.widget.AppCompatCheckBox\n'
         '                    android:id="@+id/afif_secure_network"\n'
         '                    android:layout_width="0dp"\n'
         '                    android:layout_height="wrap_content"\n'
         '                    android:layout_marginTop="16dp"\n'
         '                    android:layout_marginStart="16dp"\n'
         '                    android:layout_marginEnd="16dp"\n'
         '                    android:text="@string/afif_secure_network_option"\n'
         '                    android:textColor="?attr/color_main2_600"\n'
         '                    android:textSize="14sp"\n'
         '                    app:layout_constraintWidth_max="@dimen/text_input_max_width"\n'
         '                    app:layout_constraintTop_toBottomOf="@id/AFTER"\n'
         '                    app:layout_constraintStart_toStartOf="parent"\n'
         '                    app:layout_constraintEnd_toEndOf="parent"/>\n\n')
login = os.path.join(MAIN, "res", "layout", "assistant_third_party_sip_account_login_fragment.xml")
edit(login, "outbound_proxy_label, outbound_proxy, outbound_proxy_tooltip\"", "outbound_proxy_label, outbound_proxy, outbound_proxy_tooltip, afif_secure_network\"")
edit(login, "                <androidx.appcompat.widget.AppCompatTextView\n                    style=\"@style/primary_button_label_style\"\n                    android:onClick=\"@{() -> viewModel.login()}\"",
     CHECK.replace("AFTER", "outbound_proxy") +
     "                <androidx.appcompat.widget.AppCompatTextView\n                    style=\"@style/primary_button_label_style\"\n                    android:onClick=\"@{() -> viewModel.login()}\"")
s_login = read(login)
i = s_login.index('android:id="@+id/login"')
j = s_login.index('app:layout_constraintTop_toBottomOf="@id/outbound_proxy"', i)
write(login, s_login[:j] + 'app:layout_constraintTop_toBottomOf="@id/afif_secure_network"' + s_login[j + len('app:layout_constraintTop_toBottomOf="@id/outbound_proxy"'):])
# the tablet layout gets the same option when it has the same shape
login600 = os.path.join(MAIN, "res", "layout-sw600dp", "assistant_third_party_sip_account_login_fragment.xml")
if os.path.exists(login600):
    t = read(login600)
    anchor = "                <androidx.appcompat.widget.AppCompatTextView\n                    style=\"@style/primary_button_label_style\"\n                    android:onClick=\"@{() -> viewModel.login()}\""
    if anchor in t and 'outbound_proxy_tooltip"' in t and 'app:layout_constraintTop_toBottomOf="@id/outbound_proxy"' in t[t.index('android:id="@+id/login"'):]:
        t = t.replace("outbound_proxy_label, outbound_proxy, outbound_proxy_tooltip\"", "outbound_proxy_label, outbound_proxy, outbound_proxy_tooltip, afif_secure_network\"")
        t = t.replace(anchor, CHECK.replace("AFTER", "outbound_proxy") + anchor)
        i = t.index('android:id="@+id/login"'); j = t.index('app:layout_constraintTop_toBottomOf="@id/outbound_proxy"', i)
        t = t[:j] + 'app:layout_constraintTop_toBottomOf="@id/afif_secure_network"' + t[j + len('app:layout_constraintTop_toBottomOf="@id/outbound_proxy"'):]
        write(login600, t)
        print("  tablet login layout: option added")
# A visible connection choice in the third-party setup, so WebSocket is not hidden
# behind the separate Connection settings screen.
AFIF_WS_CHECKBOX = (
    '                <CheckBox\n'
    '                    android:id="@+id/afif_websocket"\n'
    '                    android:layout_width="0dp"\n'
    '                    android:layout_height="wrap_content"\n'
    '                    android:layout_marginStart="16dp"\n'
    '                    android:layout_marginEnd="16dp"\n'
    '                    android:layout_marginTop="8dp"\n'
    '                    android:text="Connect through AFIF WebSocket (no VPN)"\n'
    '                    android:checked="true"\n'
    '                    app:layout_constraintTop_toBottomOf="@id/domain"\n'
    '                    app:layout_constraintStart_toStartOf="parent"\n'
    '                    app:layout_constraintEnd_toEndOf="parent" />\n\n'
)
for path in [login, login600]:
    if not os.path.exists(path):
        continue
    s = read(path)
    anchor = ('                <androidx.appcompat.widget.AppCompatTextView\n'
              '                    style="@style/header_style"\n'
              '                    android:id="@+id/display_name_label"')
    if anchor in s and 'android:id="@+id/afif_websocket"' not in s:
        s = s.replace(anchor, AFIF_WS_CHECKBOX + anchor, 1)
        di = s.index('android:id="@+id/display_name_label"')
        ti = s.index('app:layout_constraintTop_toBottomOf="@id/domain"', di)
        s = s[:ti] + 'app:layout_constraintTop_toBottomOf="@id/afif_websocket"' + s[ti + len('app:layout_constraintTop_toBottomOf="@id/domain"'):]
        write(path, s)
        print(f"  websocket choice added to {os.path.basename(path)}")

lf = os.path.join(MAIN, "java", "org", "linphone", "ui", "assistant", "fragment", "ThirdPartySipAccountLoginFragment.kt")
edit(lf, "        binding.viewModel = viewModel\n", "        binding.viewModel = viewModel\n"
     "        org.linphone.AfifSecureNetwork.bind(requireActivity(), binding.afifSecureNetwork)\n"
     "        binding.afifWebsocket.isChecked = org.linphone.AfifTransport.isDomain(requireContext())\n"
     "        if (binding.afifWebsocket.isChecked) {\n"
     "            viewModel.domain.value = \"127.0.0.1:5070\"\n"
     "            binding.domain.isEnabled = false\n"
     "        }\n"
     "        binding.afifWebsocket.setOnCheckedChangeListener { _, checked ->\n"
     "            org.linphone.AfifTransport.setMode(requireContext(),\n"
     "                if (checked) org.linphone.AfifTransport.DOMAIN else org.linphone.AfifTransport.TAILSCALE)\n"
     "            if (checked) {\n"
     "                viewModel.domain.value = \"127.0.0.1:5070\"\n"
     "                binding.domain.isEnabled = false\n"
     "            } else {\n"
     "                viewModel.domain.value = org.linphone.AfifSecureNetwork.pbxDomain(requireContext())\n"
     "                binding.domain.isEnabled = true\n"
     "            }\n"
     "        }\n")
acc = os.path.join(MAIN, "res", "layout", "account_settings_fragment.xml")
edit(acc, "                        app:layout_constraintTop_toBottomOf=\"@id/advanced_settings_header\"/>\n\n",
     "                        app:layout_constraintTop_toBottomOf=\"@id/advanced_settings_header\"/>\n\n" +
     CHECK.replace("AFTER", "account_advanced_settings").replace('android:layout_height="wrap_content"\n', 'android:layout_height="wrap_content"\n'
        '                    android:layout_marginBottom="24dp"\n'
        '                    android:visibility="@{viewModel.expandAdvancedSettings ? View.VISIBLE : View.GONE}"\n', 1))
af = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "settings", "fragment", "AccountSettingsFragment.kt")
edit(af, "        binding.viewModel = viewModel\n", "        binding.viewModel = viewModel\n        org.linphone.AfifSecureNetwork.bind(requireActivity(), binding.afifSecureNetwork)\n")

print("== Keep the secure network's notification channels")
# Linphone wipes every notification channel on first start/upgrade, including the ones the Tailscale engine just
# created; its VPN service then crashes the app ("Bad notification for startForeground").
edit(os.path.join(MAIN, "java", "org", "linphone", "notifications", "NotificationsManager.kt"),
     "            for (channel in notificationManager.notificationChannels) {\n                try {",
     "            for (channel in notificationManager.notificationChannels) {\n"
     "                if (channel.id.startsWith(\"tailscale\") || channel.id == \"start_vpn_channel\") continue\n"
     "                try {")

print("== Secure network: first-run choice")
edit(os.path.join(MAIN, "java", "org", "linphone", "ui", "assistant", "AssistantActivity.kt"),
     "        binding = DataBindingUtil.setContentView(this, R.layout.assistant_activity)",
     "        binding = DataBindingUtil.setContentView(this, R.layout.assistant_activity)\n"
     "        org.linphone.AfifSecureNetwork.askOnce(this)")
edit(os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "MainActivity.kt"),
     "        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)",
     "        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)\n"
     "        org.linphone.AfifSecureNetwork.askOnce(this)")

print("== 'Secure network' in the side menu")
drawer = os.path.join(MAIN, "res", "layout", "drawer_menu.xml")
edit(drawer, 'app:layout_constraintBottom_toTopOf="@id/settings" />',
     'app:layout_constraintBottom_toTopOf="@id/afif_secure_toggle" />\n\n'
     '            <androidx.appcompat.widget.SwitchCompat\n'
     '                android:id="@+id/afif_secure_toggle"\n'
     '                android:layout_width="0dp"\n'
     '                android:layout_height="wrap_content"\n'
     '                android:layout_marginStart="10dp"\n'
     '                android:layout_marginEnd="10dp"\n'
     '                android:padding="5dp"\n'
     '                android:text="Secure network"\n'
     '                android:textColor="?attr/color_text"\n'
     '                app:layout_constraintStart_toStartOf="parent"\n'
     '                app:layout_constraintEnd_toEndOf="parent"\n'
     '                app:layout_constraintBottom_toTopOf="@id/secure_network" />\n\n'
     '            <androidx.appcompat.widget.AppCompatTextView\n'
     '                style="@style/header_style"\n'
     '                android:id="@+id/secure_network"\n'
     '                android:layout_width="0dp"\n'
     '                android:layout_height="wrap_content"\n'
     '                android:layout_marginBottom="10dp"\n'
     '                android:layout_marginStart="10dp"\n'
     '                android:layout_marginEnd="10dp"\n'
     '                android:padding="5dp"\n'
     '                android:text="@string/afif_secure_network"\n'
     '                android:drawableStart="@drawable/lock_simple"\n'
     '                android:drawableEnd="@drawable/caret_right"\n'
     '                android:drawablePadding="8dp"\n'
     '                android:background="@drawable/squircle_transparent_button_background"\n'
     '                app:drawableTint="?attr/color_main2_500"\n'
     '                app:layout_constraintStart_toStartOf="parent"\n'
     '                app:layout_constraintEnd_toEndOf="parent"\n'
     '                app:layout_constraintBottom_toTopOf="@id/settings" />')
frag = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "fragment", "DrawerMenuFragment.kt")
edit(frag, "        binding.setHelpClickedListener {",
     "        binding.secureNetwork.setOnClickListener {\n"
     "            org.linphone.AfifSecureNetwork.open(requireActivity())\n"
     "            (requireActivity() as MainActivity).closeDrawerMenu()\n"
     "        }\n"
     "        org.linphone.AfifSecureNetwork.bindToggle(requireActivity(), binding.afifSecureToggle)\n\n"
     "        binding.setHelpClickedListener {")

print("== AFIF background, battery exemption and diagnostics")
coreprefs = os.path.join(MAIN, "java", "org", "linphone", "core", "CorePreferences.kt")
edit(coreprefs, 'get() = config.getBool("app", "keep_service_alive", false)',
     'get() = config.getBool("app", "keep_service_alive", true)')

appkt = os.path.join(MAIN, "java", "org", "linphone", "LinphoneApplication.kt")
edit(appkt, "        corePreferences.config = config",
     "        corePreferences.config = config\n"
     "        // AFIF: keep the SIP stack alive for third-party accounts and survive a reboot\n"
     "        corePreferences.keepServiceAlive = true\n"
     "        corePreferences.autoStart = true\n"
     "        // AFIF: existing configs must also skip the Linphone login screen\n"
     "        corePreferences.config.setBool(\"ui\", \"assistant_go_directly_to_third_party_sip_account_login\", true)\n"
     "        corePreferences.config.setBool(\"ui\", \"assistant_disable_qr_code\", true)\n"
     "        corePreferences.config.setBool(\"ui\", \"assistant_hide_create_account\", true)\n"
     "        AfifDiagnostics.install(this)")
edit(appkt, "        coreContext.start()",
     "        coreContext.start()\n"
     "        AfifWatchdog.schedule(this)\n"
     "        AfifTransport.bootstrap(this)\n"
     "        coreContext.postOnCoreThread { core -> AfifDialPlanMigration.applyIfNeeded(core) }")

main = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "MainActivity.kt")
edit(main, "package org.linphone.ui.main",
     "package org.linphone.ui.main\n\nimport org.linphone.AfifDiagnostics")
edit(main, "        super.onCreate(savedInstanceState)",
     "        super.onCreate(savedInstanceState)\n"
     "        AfifDiagnostics.requestBatteryExemption(this)\n"
     "        AfifDiagnostics.requestNotificationPermission(this)\n"
     "        org.linphone.LinphoneApplication.coreContext.startKeepAliveService()\n"
     "        org.linphone.AfifWatchdog.schedule(this)")

# Quit stops the service and cancels the watchdog; opening the app starts them again.
edit(frag, "        binding.setQuitClickedListener {\n",
     "        binding.setQuitClickedListener {\n"
     "            org.linphone.AfifWatchdog.cancel(requireContext())\n")

manifest = os.path.join(MAIN, "AndroidManifest.xml")
edit(manifest,
     '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />',
     '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n'
     '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n'
     '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n'
     '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n'
     '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n'
     '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
     '    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />\n'
     '    <uses-permission android:name="android.permission.READ_CONTACTS" />')
edit(manifest, "<application",
     '<application\n    android:usesCleartextTraffic="true"')
edit(manifest, "</application>",
     '    <receiver android:name="org.linphone.AfifWatchdogReceiver" android:exported="false" />\n'
     '    <receiver android:name="org.linphone.AfifDomainBootReceiver" android:exported="false">\n'
     '        <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>\n'
     '    </receiver>\n'
     '    <service android:name=".AfifDomainService" android:exported="false" android:foregroundServiceType="dataSync" />\n'
     '    <service android:name=".AfifSipBridgeService" android:exported="false" android:foregroundServiceType="dataSync" />\n'
     '    <activity android:name=".AfifDomainCallActivity" android:exported="false" android:showWhenLocked="true" android:turnScreenOn="true" android:excludeFromRecents="true" android:launchMode="singleTop" />\n'
     '    <activity android:name=".AfifDomainDialActivity" android:exported="false" android:label="AFIF Direct" />\n'
     '    <activity android:name=".AfifDomainMessagesActivity" android:exported="false" android:label="AFIF Direct Messages" />\n'
     '    <activity android:name=".AfifDomainThreadActivity" android:exported="false" android:label="AFIF Direct Thread" />\n'
     '    <activity android:name=".AfifDomainVoicemailActivity" android:exported="false" android:label="AFIF Direct Voicemail" />\n'
     '    <activity android:name=".AfifDomainMainActivity" android:exported="false" android:label="AFIF Direct" />\n'
     '</application>')

# Use the PBX address that matches the current network (Tailscale or LAN).
tpvm = os.path.join(MAIN, "java", "org", "linphone", "ui", "assistant", "viewmodel", "ThirdPartySipAccountLoginViewModel.kt")
edit(tpvm, "import java.util.Locale",
     "import android.content.Context\n"
     "import android.net.ConnectivityManager\n"
     "import android.net.Network\n"
     "import android.net.NetworkCapabilities\n"
     "import java.util.Locale")
edit(tpvm, "    val outboundProxy = MutableLiveData<String>()",
     "    val outboundProxy = MutableLiveData<String>()\n\n"
     "    private val cm = coreContext.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager\n"
     "    private val networkCallback = object : ConnectivityManager.NetworkCallback() {\n"
     "        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {\n"
     "            domain.postValue(org.linphone.AfifSecureNetwork.pbxDomain(coreContext.context))\n"
     "        }\n"
     "        override fun onLost(network: Network) {\n"
     "            domain.postValue(org.linphone.AfifSecureNetwork.pbxDomain(coreContext.context))\n"
     "        }\n"
     "    }")
edit(tpvm, "        registrationInProgress.value = false",
     "        registrationInProgress.value = false\n"
     "        try { cm.registerDefaultNetworkCallback(networkCallback) } catch (_: Throwable) {}")
# Logging in here starts the local SIP bridge directly, bypassing AfifTransport.setMode()/
# bootstrap() - the only place that normally guarantees AfifDomainService isn't also running.
# bootstrap() starts AfifDomainService on every app launch whenever Domain mode is already
# selected, so a user who then explicitly logs out and back in here (the native Linphone login
# screen) ends up with BOTH services holding their own separate WebSocket registration to the
# same wss://host/register/{extension} endpoint at once. Each evicts the other's registration
# whenever either reconnects, which is the exact ~5-second registration-churn loop that was
# dropping calls a moment after they were answered. Stop AfifDomainService here too so only one
# of the two is ever alive, regardless of which path (bootstrap, setMode, or this login screen)
# started the Domain-mode transport.
edit(tpvm, "    fun login() {",
     "    override fun onCleared() {\n"
     "        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Throwable) {}\n"
     "        super.onCleared()\n"
     "    }\n\n"
     "    fun login() {\n"
     "        val afifWebSocket = org.linphone.AfifTransport.isDomain(coreContext.context)\n"
     "        if (afifWebSocket) {\n"
     "            org.linphone.AfifTransport.saveCredentials(\n"
     "                coreContext.context,\n"
     "                username.value.orEmpty(),\n"
     "                password.value.orEmpty()\n"
     "            )\n"
     "            org.linphone.AfifDomainService.stop(coreContext.context)\n"
     "            org.linphone.AfifSipBridgeService.start(coreContext.context)\n"
     "        }")
# Force the SIP identity to the local bridge when WebSocket mode is selected.
edit(tpvm, "            val domainValue = domain.value.orEmpty().trim()",
     "            val domainValue = if (org.linphone.AfifTransport.isDomain(coreContext.context))\n"
     "                \"127.0.0.1:5070\" else domain.value.orEmpty().trim()")
# This simplified login screen never shows the dial-plan/country picker that would
# normally set internationalPrefix, so it was always empty - Linphone could never
# turn a locally-dialled number like "0799031745" into "+962799031745" to match it
# against a phone contact, so incoming calls from saved contacts showed only the
# raw number. AFIF-PBX is a Jordan deployment, so default to Jordan's dial plan
# whenever the (still-hidden, still overridable) fields are empty.
edit(tpvm, "            val prefix = internationalPrefix.value.orEmpty().trim()",
     "            val prefix = internationalPrefix.value.orEmpty().trim().ifEmpty { \"962\" }")
edit(tpvm, "            val isoCountryCode = internationalPrefixIsoCountryCode.value.orEmpty()",
     "            val isoCountryCode = internationalPrefixIsoCountryCode.value.orEmpty().ifEmpty { \"JO\" }")

print("== AFIF call/message styles")
# The call fragments paint an opaque gray background over the animated theme.
# Make their roots transparent so the AFIF backdrop is actually visible.
#
# call_outgoing_fragment (the dialing/ringing screen shown before a call connects)
# was missing from this list, so it stayed solid black regardless of the chosen
# theme. Each fragment's layout-land variant, where Linphone has one, carries the
# same gray_900 background and is patched too, so rotating the phone mid-call
# doesn't bring the black screen back.
CALL_FRAGMENTS = [
    ("layout", "call_incoming_fragment.xml"),
    ("layout", "call_active_fragment.xml"),
    ("layout", "call_active_conference_fragment.xml"),
    ("layout", "call_ended_fragment.xml"),
    ("layout", "call_outgoing_fragment.xml"),
    ("layout-land", "call_incoming_fragment.xml"),
    ("layout-land", "call_active_fragment.xml"),
    ("layout-land", "call_ended_fragment.xml"),
    ("layout-land", "call_outgoing_fragment.xml"),
]
for folder, fname in CALL_FRAGMENTS:
    path = os.path.join(MAIN, "res", folder, fname)
    if os.path.exists(path):
        edit(path, 'android:background="@color/gray_900">', 'android:background="@android:color/transparent">')
    else:
        print(f"  skip {folder}/{fname} (not present in this Linphone checkout)")

# The bottom call-controls bar (hang up / video / mic / speaker, and its expanded
# bottom sheet) is a *separate* view from the fragment root above: it paints its own
# solid @color/gray_600 bar via shape_call_bottom_sheet_background, on top of
# whichever AFIF theme is showing, on every call state (incoming, outgoing, active,
# ended). Each button keeps its own background (red hang-up squircle, round mic/
# speaker buttons), so making just the bar transparent still leaves them visible,
# now floating directly over the animated AFIF backdrop. Genuine popups shown over
# the call screen (numpad, audio-device picker, call stats, conference layout) use
# the same drawable and are deliberately left opaque, since they need to stay
# readable as overlays rather than blend into the theme.
CALL_ACTION_BARS = [
    ("layout", "call_actions_generic.xml"),
    ("layout", "call_actions_bottom_sheet.xml"),
    ("layout-land", "call_actions_bottom_sheet.xml"),
    ("layout", "call_conference_actions_bottom_sheet.xml"),
    ("layout-land", "call_conference_actions_bottom_sheet.xml"),
    ("layout", "call_outgoing_actions.xml"),
    ("layout", "call_incoming_actions_screen_unlocked.xml"),
    ("layout", "call_ended_actions.xml"),
]
for folder, fname in CALL_ACTION_BARS:
    path = os.path.join(MAIN, "res", folder, fname)
    if os.path.exists(path):
        edit(path, 'android:background="@drawable/shape_call_bottom_sheet_background"',
             'android:background="@android:color/transparent"')
    else:
        print(f"  skip {folder}/{fname} (not present in this Linphone checkout)")

# The outgoing (ringing) screen's expand chevron is enabled="false" with a dim gray
# tint - it LOOKS like a live control (identical to the active-call chevron, which
# genuinely expands to Transfer/New call/Calls list/Dialpad/Messages/Pause/Record)
# but OutgoingCallFragment never wires a tap on it to anything: there's nothing to
# expand to before the call connects (hold/record/transfer only make sense for an
# answered call). That mismatch is exactly what was reported ("can't swap"). Linphone
# itself hits the same situation on the call-ended screen and solves it by hiding the
# handle with visibility="invisible" instead of leaving a dead-looking control -
# applying that same precedent here rather than a leaving a enabled="false" ImageView
# that still looks tappable.
call_outgoing_actions = os.path.join(MAIN, "res", "layout", "call_outgoing_actions.xml")
edit(call_outgoing_actions,
     '            android:padding="11dp"\n'
     '            android:src="@drawable/animated_caret_to_handle"\n'
     '            android:enabled="false"\n'
     '            android:contentDescription="@string/content_description_toggle_bottom_sheet"\n'
     '            app:tint="@color/gray_400"',
     '            android:padding="11dp"\n'
     '            android:src="@drawable/animated_caret_to_handle"\n'
     '            android:visibility="invisible"\n'
     '            android:contentDescription="@string/content_description_toggle_bottom_sheet"\n'
     '            app:tint="@color/gray_400"')

# Rebuild the outgoing (ringing) screen's control row into a labeled 3x3 grid
# (Record / Mute / Hold, Transfer / Video / Messages, Speaker / End / Dialpad)
# matching the grid-style layout Afif asked for. Every tile is a real AFIF
# Phone action - Record, Hold, Messages and Dialpad use the exact same
# viewModel calls and enabled-state bindings as the working active-call grid
# (call_actions_bottom_sheet.xml), so they light up on their own the instant
# the call connects. Transfer and Video are hardcoded enabled="false" with
# their real icon/label, because transferring or toggling video genuinely
# can't happen before this call is answered - upstream itself already does
# this for Video; Transfer just gets the same treatment rather than wiring a
# fragment-side click listener for a button with nothing valid to do yet.
# No fabricated buttons (no fake "AI summary" / "noise cancellation" tiles).
# Needs its own (taller) bar height, so a new dimen is introduced rather than
# touching call_main_actions_menu_height, which the incoming/active/ended
# bars still rely on unchanged.
dimen_default = os.path.join(MAIN, "res", "values", "dimen.xml")
edit(dimen_default,
     '    <dimen name="call_main_actions_menu_height">110dp</dimen>\n'
     '    <dimen name="call_all_actions_menu_height">345dp</dimen> <!-- sum of above two -->',
     '    <dimen name="call_main_actions_menu_height">110dp</dimen>\n'
     '    <dimen name="call_all_actions_menu_height">345dp</dimen> <!-- sum of above two -->\n'
     '    <dimen name="call_outgoing_actions_menu_height">330dp</dimen> <!-- taller bar, bigger buttons to match the reference screenshot -->')

dimen_land = os.path.join(MAIN, "res", "values-land", "dimen.xml")
edit(dimen_land,
     '    <dimen name="call_main_actions_menu_height">110dp</dimen>\n'
     '    <dimen name="call_all_actions_menu_height">235dp</dimen> <!-- sum of above two -->',
     '    <dimen name="call_main_actions_menu_height">110dp</dimen>\n'
     '    <dimen name="call_all_actions_menu_height">235dp</dimen> <!-- sum of above two -->\n'
     '    <dimen name="call_outgoing_actions_menu_height">250dp</dimen> <!-- taller bar, bigger buttons to match the reference screenshot -->')

for fname in ["call_outgoing_fragment.xml"]:
    for folder in ["layout", "layout-land"]:
        path = os.path.join(MAIN, "res", folder, fname)
        edit(path,
             'android:layout_height="@dimen/call_main_actions_menu_height"',
             'android:layout_height="@dimen/call_outgoing_actions_menu_height"')

with open(call_outgoing_actions, "r", encoding="utf-8") as f:
    _content = f.read()
_start_marker = '        <ImageView\n            android:onClick="@{() -> viewModel.hangUp()}"'
_end_marker = '    </androidx.constraintlayout.widget.ConstraintLayout>'
_start_idx = _content.find(_start_marker)
_end_idx = _content.find(_end_marker)
if _start_idx == -1 or _end_idx == -1:
    sys.exit("call_outgoing_actions.xml: grid-rebuild markers not found - upstream layout changed")
_grid = '''        <ImageView
            android:id="@+id/record_call"
            android:onClick="@{() -> viewModel.toggleRecording()}"
            android:enabled="@{viewModel.isRecordingEnabled &amp;&amp; !viewModel.isPaused &amp;&amp; !viewModel.isPausedByRemote}"
            android:selected="@{viewModel.isRecording()}"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="14dp"
            android:padding="14dp"
            android:src="@drawable/record_fill"
            android:contentDescription="@string/call_action_record_call"
            android:background="@drawable/in_call_button_background_red"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/call_actions_handle"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.17" />

        <TextView
            android:id="@+id/record_call_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:enabled="@{viewModel.isRecordingEnabled &amp;&amp; !viewModel.isPaused &amp;&amp; !viewModel.isPausedByRemote}"
            android:text="@{viewModel.isRecording ? @string/call_action_stop_record_call : @string/call_action_record_call, default=@string/call_action_record_call}"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/record_call"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.17" />

        <org.linphone.ui.call.view.VuMeterView
            android:id="@+id/record_volume_vu_meter"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:background="@drawable/in_call_button_background_red"
            vuMeterPercentage="@{viewModel.microphoneRecordingVolume}"
            app:layout_constraintTop_toTopOf="@id/toggle_mute_mic"
            app:layout_constraintBottom_toBottomOf="@id/toggle_mute_mic"
            app:layout_constraintStart_toStartOf="@id/toggle_mute_mic"
            app:layout_constraintEnd_toEndOf="@id/toggle_mute_mic" />

        <ImageView
            android:onClick="@{() -> viewModel.toggleMuteMicrophone()}"
            android:id="@+id/toggle_mute_mic"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="14dp"
            android:padding="14dp"
            android:src="@{viewModel.isMicrophoneMuted ? @drawable/microphone_slash : @drawable/microphone, default=@drawable/microphone}"
            android:contentDescription="@string/content_description_toggle_microphone"
            android:background="@drawable/in_call_button_background_transparent"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/call_actions_handle"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.5" />

        <TextView
            android:id="@+id/toggle_mute_mic_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="Mute"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_mute_mic"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.5" />

        <ImageView
            android:onClick="@{() -> viewModel.togglePause()}"
            android:id="@+id/pause_call"
            android:enabled="@{viewModel.canBePaused}"
            android:selected="@{viewModel.isPaused}"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="14dp"
            android:padding="14dp"
            android:src="@{viewModel.isPaused ? @drawable/play : @drawable/pause, default=@drawable/pause}"
            android:contentDescription="@{viewModel.isPaused ? @string/call_action_resume_call : @string/call_action_pause_call}"
            android:background="@drawable/in_call_button_background_green"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/call_actions_handle"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.83" />

        <TextView
            android:id="@+id/pause_call_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:enabled="@{viewModel.canBePaused}"
            android:text="@{viewModel.isPaused ? @string/call_action_resume_call : @string/call_action_pause_call, default=@string/call_action_pause_call}"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/pause_call"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.83" />

        <ImageView
            android:id="@+id/transfer"
            android:enabled="false"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:src="@drawable/phone_transfer"
            android:contentDescription="@string/call_action_transfer"
            android:background="@drawable/shape_round_in_call_disabled_button_background"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_mute_mic_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.17" />

        <TextView
            android:id="@+id/transfer_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:enabled="false"
            android:text="@string/call_action_transfer"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/transfer"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.17" />

        <ImageView
            android:id="@+id/toggle_video"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:visibility="@{viewModel.hideVideo ? View.GONE : View.VISIBLE}"
            android:contentDescription="@null"
            android:enabled="@{false}"
            android:src="@{viewModel.isVideoEnabled ? @drawable/video_camera : @drawable/video_camera_slash, default=@drawable/video_camera}"
            android:background="@drawable/shape_round_in_call_disabled_button_background"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_mute_mic_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.5" />

        <TextView
            android:id="@+id/toggle_video_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="Video"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_video"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.5" />

        <ImageView
            android:id="@+id/chat"
            android:onClick="@{() -> viewModel.createConversation()}"
            android:enabled="@{!viewModel.operationInProgress}"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:src="@drawable/chat_teardrop_text"
            android:contentDescription="@string/call_action_show_messages"
            android:background="@drawable/in_call_button_background_red"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_mute_mic_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.83" />

        <TextView
            android:id="@+id/chat_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:enabled="@{!viewModel.operationInProgress}"
            android:text="@string/call_action_show_messages"
            android:textSize="12sp"
            android:textColor="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/chat"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.83" />

        <org.linphone.ui.call.view.VuMeterView
            android:id="@+id/playback_volume_vu_meter"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:background="@drawable/in_call_button_background_red"
            vuMeterPercentage="@{viewModel.playbackVolume}"
            app:layout_constraintTop_toTopOf="@id/change_audio_output"
            app:layout_constraintBottom_toBottomOf="@id/change_audio_output"
            app:layout_constraintStart_toStartOf="@id/change_audio_output"
            app:layout_constraintEnd_toEndOf="@id/change_audio_output" />

        <ImageView
            android:onClick="@{() -> viewModel.changeAudioOutputDevice()}"
            android:id="@+id/change_audio_output"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:enabled="@{viewModel.isOutgoingRinging}"
            android:contentDescription="@string/content_description_change_output_audio_device"
            android:src="@{viewModel.isHeadsetEnabled ? @drawable/headset : viewModel.isBluetoothEnabled ? @drawable/bluetooth : viewModel.isSpeakerEnabled ? @drawable/speaker_high : @drawable/speaker_slash, default=@drawable/speaker_slash}"
            android:background="@drawable/in_call_button_background_transparent"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_video_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.17" />

        <ImageView
            android:onClick="@{() -> viewModel.hangUp()}"
            android:id="@+id/hang_up"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:src="@drawable/phone_disconnect"
            android:contentDescription="@string/content_description_hang_up_call"
            android:background="@drawable/squircle_red_button_background"
            app:tint="@color/bc_white"
            app:layout_constraintTop_toBottomOf="@id/toggle_video_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.5" />

        <ImageView
            android:id="@+id/numpad"
            android:onClick="@{() -> viewModel.showNumpad()}"
            android:enabled="@{!viewModel.isPaused &amp;&amp; !viewModel.isPausedByRemote}"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_marginTop="16dp"
            android:padding="14dp"
            android:src="@drawable/numpad"
            android:contentDescription="@string/call_action_show_dialer"
            android:background="@drawable/in_call_button_background_red"
            app:tint="@color/in_call_button_tint_color"
            app:layout_constraintTop_toBottomOf="@id/toggle_video_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintHorizontal_bias="0.83" />

'''
_content = _content[:_start_idx] + _grid + _content[_end_idx:]
with open(call_outgoing_actions, "w", encoding="utf-8") as f:
    f.write(_content)
print("  rebuilt call_outgoing_actions.xml into a labeled 3x3 control grid")

ui = os.path.join(MAIN, "res", "layout", "settings_user_interface.xml")
edit(ui, "    </androidx.constraintlayout.widget.ConstraintLayout>\n\n</layout>",
     '        <androidx.appcompat.widget.AppCompatTextView\n'
     '            style="@style/settings_title_padding_style"\n'
     '            android:id="@+id/afif_style_title"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="wrap_content"\n'
     '            android:layout_marginTop="20dp"\n'
     '            android:layout_marginStart="16dp"\n'
     '            android:layout_marginEnd="16dp"\n'
     '            android:text="Call screen theme"\n'
     '            app:layout_constraintTop_toBottomOf="@id/color_spinner"\n'
     '            app:layout_constraintStart_toStartOf="parent"\n'
     '            app:layout_constraintEnd_toEndOf="parent"/>\n\n'
     '        <androidx.appcompat.widget.AppCompatSpinner\n'
     '            style="@style/default_text_style"\n'
     '            android:id="@+id/afif_style_spinner"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:background="@drawable/edit_text_background"\n'
     '            android:paddingStart="@dimen/spinner_start_padding"\n'
     '            android:paddingEnd="@dimen/spinner_end_padding"\n'
     '            android:overlapAnchor="false"\n'
     '            android:spinnerMode="dropdown"\n'
     '            android:popupBackground="@drawable/shape_squircle_white_background"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_style_title"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_style_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_style_title" />\n\n'
     '        <androidx.appcompat.widget.AppCompatTextView\n'
     '            style="@style/settings_title_padding_style"\n'
     '            android:id="@+id/afif_layout_title"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="wrap_content"\n'
     '            android:layout_marginTop="20dp"\n'
     '            android:layout_marginStart="16dp"\n'
     '            android:layout_marginEnd="16dp"\n'
     '            android:text="Call button layout"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_style_spinner"\n'
     '            app:layout_constraintStart_toStartOf="parent"\n'
     '            app:layout_constraintEnd_toEndOf="parent"/>\n\n'
     '        <androidx.appcompat.widget.AppCompatSpinner\n'
     '            style="@style/default_text_style"\n'
     '            android:id="@+id/afif_layout_spinner"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:background="@drawable/edit_text_background"\n'
     '            android:paddingStart="@dimen/spinner_start_padding"\n'
     '            android:paddingEnd="@dimen/spinner_end_padding"\n'
     '            android:overlapAnchor="false"\n'
     '            android:spinnerMode="dropdown"\n'
     '            android:popupBackground="@drawable/shape_squircle_white_background"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_layout_title"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_layout_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_layout_title" />\n\n'
     '        <androidx.appcompat.widget.AppCompatTextView\n'
     '            style="@style/settings_title_padding_style"\n'
     '            android:id="@+id/afif_message_title"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="wrap_content"\n'
     '            android:layout_marginTop="20dp"\n'
     '            android:layout_marginStart="16dp"\n'
     '            android:layout_marginEnd="16dp"\n'
     '            android:text="Message screen theme"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_layout_spinner"\n'
     '            app:layout_constraintStart_toStartOf="parent"\n'
     '            app:layout_constraintEnd_toEndOf="parent"/>\n\n'
     '        <androidx.appcompat.widget.AppCompatSpinner\n'
     '            style="@style/default_text_style"\n'
     '            android:id="@+id/afif_message_spinner"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:background="@drawable/edit_text_background"\n'
     '            android:paddingStart="@dimen/spinner_start_padding"\n'
     '            android:paddingEnd="@dimen/spinner_end_padding"\n'
     '            android:overlapAnchor="false"\n'
     '            android:spinnerMode="dropdown"\n'
     '            android:popupBackground="@drawable/shape_squircle_white_background"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_message_title"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_message_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_message_title" />\n\n'
     '        <androidx.appcompat.widget.AppCompatTextView\n'
     '            style="@style/settings_title_padding_style"\n'
     '            android:id="@+id/afif_voice_title"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="wrap_content"\n'
     '            android:layout_marginTop="20dp"\n'
     '            android:layout_marginStart="16dp"\n'
     '            android:layout_marginEnd="16dp"\n'
     '            android:text="Voice changer"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_message_spinner"\n'
     '            app:layout_constraintStart_toStartOf="parent"\n'
     '            app:layout_constraintEnd_toEndOf="parent"/>\n\n'
     '        <androidx.appcompat.widget.AppCompatSpinner\n'
     '            style="@style/default_text_style"\n'
     '            android:id="@+id/afif_voice_spinner"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:background="@drawable/edit_text_background"\n'
     '            android:paddingStart="@dimen/spinner_start_padding"\n'
     '            android:paddingEnd="@dimen/spinner_end_padding"\n'
     '            android:overlapAnchor="false"\n'
     '            android:spinnerMode="dropdown"\n'
     '            android:popupBackground="@drawable/shape_squircle_white_background"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_voice_title"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_voice_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_voice_title" />\n\n'
     '        <androidx.appcompat.widget.AppCompatTextView\n'
     '            style="@style/settings_title_padding_style"\n'
     '            android:id="@+id/afif_connection_title"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="wrap_content"\n'
     '            android:layout_marginTop="20dp"\n'
     '            android:layout_marginStart="16dp"\n'
     '            android:layout_marginEnd="16dp"\n'
     '            android:text="Connection"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_voice_spinner"\n'
     '            app:layout_constraintStart_toStartOf="parent"\n'
     '            app:layout_constraintEnd_toEndOf="parent"/>\n\n'
     '        <androidx.appcompat.widget.AppCompatSpinner\n'
     '            style="@style/default_text_style"\n'
     '            android:id="@+id/afif_connection_spinner"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:background="@drawable/edit_text_background"\n'
     '            android:paddingStart="@dimen/spinner_start_padding"\n'
     '            android:paddingEnd="@dimen/spinner_end_padding"\n'
     '            android:overlapAnchor="false"\n'
     '            android:spinnerMode="dropdown"\n'
     '            android:popupBackground="@drawable/shape_squircle_white_background"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_connection_title"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_connection_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_connection_title" />\n\n'
     '        <Button\n'
     '            android:id="@+id/afif_domain_dial"\n'
     '            android:layout_width="0dp"\n'
     '            android:layout_height="50dp"\n'
     '            android:layout_marginTop="10dp"\n'
     '            android:text="Open direct dialer"\n'
     '            app:layout_constraintTop_toBottomOf="@id/afif_connection_spinner"\n'
     '            app:layout_constraintStart_toStartOf="@id/afif_connection_title"\n'
     '            app:layout_constraintEnd_toEndOf="@id/afif_connection_title" />\n\n'
     '    </androidx.constraintlayout.widget.ConstraintLayout>\n\n</layout>')

settings_fragment = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "settings", "fragment", "SettingsFragment.kt")
edit(settings_fragment, "        binding.viewModel = viewModel",
     "        binding.viewModel = viewModel\n"
     "        val afifStyleAdapter = android.widget.ArrayAdapter(\n"
     "            requireContext(), R.layout.generic_dropdown_cell, org.linphone.AfifTheme.labels()\n"
     "        )\n"
     "        binding.userInterfaceSettings.afifStyleSpinner.adapter = afifStyleAdapter\n"
     "        binding.userInterfaceSettings.afifStyleSpinner.setSelection(org.linphone.AfifTheme.index(requireContext()))\n"
     "        binding.userInterfaceSettings.afifStyleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n"
     "            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n"
     "                org.linphone.AfifTheme.setIndex(requireContext(), position)\n"
     "            }\n"
     "            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}\n"
     "        }\n"
     "        val afifLayoutAdapter = android.widget.ArrayAdapter(\n"
     "            requireContext(), R.layout.generic_dropdown_cell, org.linphone.AfifCallLayout.labels()\n"
     "        )\n"
     "        binding.userInterfaceSettings.afifLayoutSpinner.adapter = afifLayoutAdapter\n"
     "        binding.userInterfaceSettings.afifLayoutSpinner.setSelection(org.linphone.AfifCallLayout.index(requireContext()))\n"
     "        binding.userInterfaceSettings.afifLayoutSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n"
     "            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n"
     "                org.linphone.AfifCallLayout.setIndex(requireContext(), position)\n"
     "            }\n"
     "            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}\n"
     "        }\n"
     "        val afifMessageAdapter = android.widget.ArrayAdapter(\n"
     "            requireContext(), R.layout.generic_dropdown_cell, org.linphone.AfifTheme.labels()\n"
     "        )\n"
     "        binding.userInterfaceSettings.afifMessageSpinner.adapter = afifMessageAdapter\n"
     "        binding.userInterfaceSettings.afifMessageSpinner.setSelection(org.linphone.AfifTheme.messageIndex(requireContext()))\n"
     "        binding.userInterfaceSettings.afifMessageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n"
     "            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n"
     "                org.linphone.AfifTheme.setMessageIndex(requireContext(), position)\n"
     "            }\n"
     "            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}\n"
     "        }\n"
     "        val afifVoiceAdapter = android.widget.ArrayAdapter(\n"
     "            requireContext(), R.layout.generic_dropdown_cell, org.linphone.AfifVoiceEffect.effects\n"
     "        )\n"
     "        binding.userInterfaceSettings.afifVoiceSpinner.adapter = afifVoiceAdapter\n"
     "        binding.userInterfaceSettings.afifVoiceSpinner.setSelection(org.linphone.AfifVoiceEffect.current(requireContext()))\n"
     "        binding.userInterfaceSettings.afifVoiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n"
     "            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n"
     "                org.linphone.AfifVoiceEffect.setIndex(requireActivity(), position)\n"
     "            }\n"
     "            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}\n"
     "        }\n"
     "        val afifTransportAdapter = android.widget.ArrayAdapter(\n"
     "            requireContext(), R.layout.generic_dropdown_cell, org.linphone.AfifTransport.labels()\n"
     "        )\n"
     "        binding.userInterfaceSettings.afifConnectionSpinner.adapter = afifTransportAdapter\n"
     "        binding.userInterfaceSettings.afifConnectionSpinner.setSelection(org.linphone.AfifTransport.index(requireContext()))\n"
     "        binding.userInterfaceSettings.afifConnectionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n"
     "            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n"
     "                org.linphone.AfifTransport.setIndex(requireActivity(), position)\n"
     "            }\n"
     "            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}\n"
     "        }\n"
     "        binding.userInterfaceSettings.afifDomainDial.setOnClickListener {\n"
     "            startActivity(android.content.Intent(requireContext(), org.linphone.AfifDomainDialActivity::class.java))\n"
     "        }")

call_activity = os.path.join(MAIN, "java", "org", "linphone", "ui", "call", "CallActivity.kt")
edit(call_activity, "        super.onCreate(savedInstanceState)",
     "        super.onCreate(savedInstanceState)\n"
     "        org.linphone.AfifTheme.applyCallWindow(this)")
edit(call_activity, "        binding = DataBindingUtil.setContentView(this, R.layout.call_activity)",
     "        binding = DataBindingUtil.setContentView(this, R.layout.call_activity)\n"
     "        org.linphone.AfifTheme.applyCall(this, binding.root)\n"
     "        org.linphone.AfifCallBackdrop.install(this, binding.root)\n"
     "        binding.root.post {\n"
     "            org.linphone.AfifTheme.applyCallControls(binding.root)\n"
     "            org.linphone.AfifVoiceEffect.install(this, binding.root)\n"
     "        }")

# The CallActivity tint above only runs once, right after the activity's own layout
# settles. Each call state (outgoing/incoming/active/ended) is a separate fragment
# that Navigation swaps in afterwards, inflating its own fresh copy of the hang-up/
# video/mic/speaker buttons - never touched by that one-shot call. Result: the
# buttons look flat gray/black while everything else is themed. GenericCallFragment
# is the shared base class for all four call-state fragments, and its onViewCreated
# runs every time any of them is (re)created, so hooking there re-applies the tint
# on every state transition instead of just once at startup.
generic_call_fragment = os.path.join(MAIN, "java", "org", "linphone", "ui", "call", "fragment", "GenericCallFragment.kt")
edit(generic_call_fragment,
     "        sharedViewModel = requireActivity().run {\n"
     "            ViewModelProvider(this)[SharedCallViewModel::class.java]\n"
     "        }\n"
     "    }",
     "        sharedViewModel = requireActivity().run {\n"
     "            ViewModelProvider(this)[SharedCallViewModel::class.java]\n"
     "        }\n\n"
     "        org.linphone.AfifTheme.applyCallControls(view)\n"
     "        org.linphone.AfifCallLayoutOverlay.install(view)\n"
     "    }")

conversation = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "chat", "fragment", "ConversationFragment.kt")
edit(conversation, "        binding = ChatConversationFragmentBinding.inflate(layoutInflater)",
     "        binding = ChatConversationFragmentBinding.inflate(layoutInflater)\n"
     "        org.linphone.AfifTheme.applyChat(binding.root)")

chat_adapter = os.path.join(MAIN, "java", "org", "linphone", "ui", "main", "chat", "adapter", "ConversationEventAdapter.kt")
edit(chat_adapter, "        val viewHolder = IncomingBubbleViewHolder(binding)",
     "        val viewHolder = IncomingBubbleViewHolder(binding)\n"
     "        org.linphone.AfifTheme.applyBubble(binding.bubble, false)")
edit(chat_adapter, "        val viewHolder = OutgoingBubbleViewHolder(binding)",
     "        val viewHolder = OutgoingBubbleViewHolder(binding)\n"
     "        org.linphone.AfifTheme.applyBubble(binding.bubble, true)")

print("AFIF Phone overlay applied.")
