f = r"C:\cineflight_android\CineFlightSolo\app\src\main\jni\CMakeLists.txt"
s = open(f, encoding="utf-8").read()

# Sauvegarde de l'ancienne ligne opencv-mobile en commentaire, remplacement par OpenCV officiel statique
old_ocv = "set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.13.0-android/sdk/native/jni)\nfind_package(OpenCV REQUIRED core imgproc)"
new_ocv = '''# OpenCV officiel (statique) - inclut objdetect/aruco
set(OpenCV_STATIC ON)
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/OpenCV-android-sdk/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc objdetect)'''

if old_ocv in s:
    s = s.replace(old_ocv, new_ocv, 1)
    print("CMakeLists: OpenCV officiel + objdetect configure")
elif "OpenCV-android-sdk" in s:
    print("DEJA configure")
else:
    print("ANCRE NON TROUVEE - montrer le CMakeLists")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("--- CMakeLists actuel ---")
print(s)