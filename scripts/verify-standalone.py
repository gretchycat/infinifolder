#!/usr/bin/env python3
"""Build fresh isolated GitHub checkouts and record tests, dependency pins and APK contents."""
from pathlib import Path
import argparse, hashlib, json, os, struct, subprocess, sys, tempfile, time, zipfile
import xml.etree.ElementTree as ET

TASKS={
'infinifolder':[':app:assembleDebug',':app:testDebugUnitTest',':storage-engine:testDebugUnitTest',':viewer-hex:testDebugUnitTest'],
'infiniscribe':['assembleDebug','testDebugUnitTest'],
'infinimedia':['assembleDebug','testDebugUnitTest'],
'infinikey-ime':[':app:assembleDebug',':app:testDebugUnitTest'],
}

def defined_classes(data):
    def u32(at): return struct.unpack_from('<I',data,at)[0]
    strings=u32(60);types=u32(68);count=u32(96);classes=u32(100)
    def string(index):
        pos=u32(strings+index*4)
        while data[pos]&128:pos+=1
        pos+=1
        return data[pos:data.index(b'\0',pos)].decode('utf-8',errors='replace')
    return {string(u32(types+u32(classes+i*32)*4)) for i in range(count)}

def inspect_apk(path):
    with zipfile.ZipFile(path) as archive:
        files=archive.infolist();classes=set()
        for item in files:
            if item.filename.startswith('classes') and item.filename.endswith('.dex'):
                classes.update(defined_classes(archive.read(item)))
        return {'path':str(path),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'native_libraries':[i.filename for i in files if i.filename.endswith('.so')],
            'notices':[i.filename for i in files if i.filename.startswith('assets/third-party/')],
            'largest_entries':[{'name':i.filename,'compressed':i.compress_size,'bytes':i.file_size}
                for i in sorted(files,key=lambda i:i.compress_size,reverse=True)[:20]],
            'application_classes':{n:('L'+n.replace('.','/')+';') in classes for n in [
                'com.infinifolder.MainActivity','com.infiniscribe.InfiniScribeActivity','com.infinimedia.InfiniMediaActivity',
                'com.infinifolder.viewer.AudioPlayerActivity','com.infinifolder.viewer.VideoPlayerActivity',
                'com.infinifolder.viewer.TextViewerActivity','com.infinifolder.viewer.MindNodeTouchViewerActivity',
                'com.infinifolder.storage.RoutingStorageProvider','com.infinifolder.archive.ArchiveStorageProvider',
                'com.infinifolder.sftp.SftpStorageProvider','com.infinifolder.torrent.MagnetStorageProvider']}}

def test_results(checkout):
    suites=[]
    for path in checkout.rglob('TEST-*.xml'):
        if 'test-results' not in path.parts:continue
        element=ET.parse(path).getroot()
        suites.append({'path':str(path.relative_to(checkout)),**{name:int(element.attrib.get(name,0))
            for name in ['tests','failures','errors','skipped']}})
    return suites

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repositories',nargs='+',choices=list(TASKS))
    parser.add_argument('--sdk',default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--output',type=Path,default=Path('build/standalone-verification'))
    parser.add_argument('--abi',default='arm64-v8a')
    args=parser.parse_args()
    if not args.sdk:parser.error('Set ANDROID_HOME or supply --sdk')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    guard=Path(__file__).with_name('assert-checkout.gradle').resolve()
    results=[]
    for name in args.repositories:
        directory=Path(tempfile.mkdtemp(prefix='infini-'+name+'-'));checkout=directory/'checkout'
        report={'repository':name,'directory':str(directory),'started':time.time()};results.append(report)
        log=output/(name+'.log');report['log']=str(log)
        with log.open('w') as stream:
            def run(command):
                stream.write('$ '+' '.join(map(str,command))+'\n');stream.flush()
                subprocess.run(command,cwd=checkout if checkout.exists() else directory,
                    stdout=stream,stderr=subprocess.STDOUT,check=True)
            try:
                print(name+': cloning into '+str(checkout),flush=True)
                run(['git','clone','https://github.com/gretchycat/'+name+'.git',str(checkout)])
                report['commit']=subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip()
                (checkout/'local.properties').write_text('sdk.dir='+str(Path(args.sdk).resolve())+'\n')
                if (checkout/'dependencies.lock.json').exists():
                    run([sys.executable,'scripts/fetch-dependencies.py'])
                    run([sys.executable,'scripts/fetch-dependencies.py','--check'])
                    report['dependencies']=json.loads((checkout/'dependencies.lock.json').read_text())['repositories']
                assert set(p.name for p in directory.iterdir())=={'checkout'},'Unexpected sibling checkout'
                copied=checkout/'build-assert-checkout.gradle';copied.write_bytes(guard.read_bytes())
                report['command']=['./gradlew',*TASKS[name],'-PtargetAbi='+args.abi,'--max-workers=2','--no-build-cache',
                    '--console=plain','--init-script',str(copied),'-Dinfini.verifyCheckout='+str(checkout.resolve())]
                print(name+': building and testing; '+str(log),flush=True)
                run(report['command'])
                report['tests']=test_results(checkout)
                apks=[p for p in checkout.rglob('*.apk') if '/outputs/apk/debug/' in str(p)]
                if not apks:raise RuntimeError('No debug APK produced')
                report['apks']=[inspect_apk(p) for p in apks]
                for apk in report['apks']:
                    classes=apk['application_classes']
                    if name!='infinifolder' and any(classes[c] for c in ['com.infinifolder.MainActivity',
                        'com.infinifolder.storage.RoutingStorageProvider','com.infinifolder.archive.ArchiveStorageProvider',
                        'com.infinifolder.sftp.SftpStorageProvider','com.infinifolder.torrent.MagnetStorageProvider']):
                        raise RuntimeError('Unexpected Infinifolder/storage implementation packaged')
                    if name!='infinimedia' and any(classes[c] for c in ['com.infinifolder.viewer.AudioPlayerActivity', 'com.infinifolder.viewer.VideoPlayerActivity']):
                        raise RuntimeError('Unexpected media-player application code packaged')
                    if name!='infiniscribe' and any(classes[c] for c in ['com.infinifolder.viewer.TextViewerActivity', 'com.infinifolder.viewer.MindNodeTouchViewerActivity']):
                        raise RuntimeError('Unexpected document-editor application code packaged')
                report['result']='passed'
            except Exception as error:
                report['result']='failed';report['error']=str(error)
                print(name+': FAILED: '+str(error)+'; see '+str(log),flush=True)
        report['seconds']=round(time.time()-report['started'],2)
        (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')
        print(name+': '+report['result'],flush=True)
    return int(any(r['result']!='passed' for r in results))
if __name__=='__main__':sys.exit(main())
