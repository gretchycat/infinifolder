#!/usr/bin/env python3
"""Run descriptor primitives against a built APK on a local Android app_process runtime."""
from pathlib import Path
import argparse,os,subprocess,tempfile,hashlib,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[1]
android=a.sdk/'platforms/android-36/android.jar'
select=root/'libraries/libinfiniselect/select-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'
kotlin=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
with tempfile.TemporaryDirectory(prefix='infini-storage-runtime-') as directory:
 work=Path(directory);classes=work/'classes';dex=work/'dex';classes.mkdir();dex.mkdir()
 classpath=os.pathsep.join(map(str,[android,select,kotlin]))
 subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac'),'--release','17','-cp',classpath,'-d',str(classes),str(root/'scripts/runtime/StorageBoundaryRuntimeCheck.java')],check=True)
 d8=a.sdk/'build-tools/35.0.0/d8'
 subprocess.run([str(d8),'--min-api','26','--lib',str(android),'--classpath',str(select),'--classpath',str(kotlin),
     '--output',str(dex),*map(str,classes.glob('*.class'))],check=True)
 (dex/'classes.dex').chmod(0o444)
 env=os.environ.copy();env.pop('LD_PRELOAD',None);env.pop('LD_LIBRARY_PATH',None)
 runtime_apk=work/'runtime.apk';shutil.copyfile(a.apk,runtime_apk);runtime_apk.chmod(0o444)
 env['CLASSPATH']=str(dex/'classes.dex')+os.pathsep+str(runtime_apk)
 subprocess.run(['/system/bin/app_process64','/system/bin','StorageBoundaryRuntimeCheck',str(work)],env=env,check=True,timeout=60)
 print('APK SHA-256:',hashlib.sha256(a.apk.read_bytes()).hexdigest())
