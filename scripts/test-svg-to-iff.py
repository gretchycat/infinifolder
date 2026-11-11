#!/usr/bin/env python3
"""Regression checks with the real SVG renderer, ILBM bitplanes and cycle metadata."""
from pathlib import Path
import importlib.util,shutil,struct,subprocess,sys,tempfile,unittest
from PIL import Image
SCRIPT=Path(__file__).with_name('svg-to-iff.py').resolve()
spec=importlib.util.spec_from_file_location('svg_to_iff',SCRIPT);m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
RENDERER=shutil.which('rsvg-convert')
SVG="""<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='20' height='10' viewBox='0 0 20 10'><metadata><i:palette id='p' size='3'><i:color index='0' value='#f00'/><i:color index='1' value='#0f0'/><i:color index='2' value='#00f'/></i:palette><i:cycle palette='p' start='0' end='2' rate='2'/></metadata><rect width='20' height='10' fill='#f00' i:palette-index='0'/></svg>"""
class ConverterTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='svg-iff-test-');self.root=Path(self.tmp.name);self.svg=self.root/'art.svg';self.svg.write_text(SVG)
    def tearDown(self):self.tmp.cleanup()
    def run_cli(self,*args):return subprocess.run([sys.executable,str(SCRIPT),str(self.svg),*map(str,args)],capture_output=True,text=True)
    def test_intrinsic_and_viewbox_aspect(self):
        self.assertEqual(2,m.svg_aspect_ratio(self.svg))
        self.svg.write_text("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0,0,30,10'/>")
        self.assertEqual(3,m.svg_aspect_ratio(self.svg))
    @unittest.skipUnless(RENDERER,'rsvg-convert required')
    def test_fit_and_content_box_centering(self):
        full=m.render_svg(self.svg,40,40,RENDERER)
        self.assertEqual((0,10,40,30),full.getbbox())
        fitted=m.render_svg(self.svg,40,40,RENDERER,(10,5,20,30))
        self.assertEqual((10,15,30,25),fitted.getbbox())
    @unittest.skipUnless(RENDERER,'rsvg-convert required')
    def test_background_is_static_and_foreground_keeps_cycle_indices(self):
        background=self.root/'background.png';Image.new('RGB',(40,40),(128,128,128)).save(background)
        output=self.root/'composite.iff'
        result=self.run_cli(output,'--width',40,'--height',40,'--background',background)
        self.assertEqual(0,result.returncode,result.stderr)
        data=output.read_bytes();self.assertEqual(b'FORM',data[:4]);self.assertEqual(len(data)-8,struct.unpack_from('>I',data,4)[0])
        chunks={};offset=12
        while offset+8<=len(data):
            size=struct.unpack_from('>I',data,offset+4)[0];chunks[data[offset:offset+4]]=data[offset+8:offset+8+size];offset+=8+size+(size&1)
        first,last=struct.unpack('>HHHBB',chunks[b'CRNG'])[3:5]
        row=((40+15)//16)*2;body=chunks[b'BODY']
        def index(x,y):return sum((1<<p) for p in range(8) if body[y*row*9+p*row+x//8]&(128>>(x%8)))
        self.assertNotIn(index(0,0),range(first,last+1))
        self.assertIn(index(20,20),range(first,last+1))
        rgb=chunks[b'CMAP'][index(0,0)*3:index(0,0)*3+3];self.assertEqual(bytes([128]*3),rgb)
        self.assertTrue(all(body[y*row*9+8*row+x//8]&(128>>(x%8)) for y in range(40) for x in range(40)))
    @unittest.skipUnless(RENDERER,'rsvg-convert required')
    def test_mismatched_background_fails_without_output(self):
        base=self.root/'base.png';Image.new('RGB',(3,3)).save(base);out=self.root/'out.iff'
        result=self.run_cli(out,'--width',40,'--height',40,'--background',base)
        self.assertNotEqual(0,result.returncode);self.assertFalse(out.exists())
    def test_background_cannot_be_overwritten(self):
        base=self.root/'base.png';Image.new('RGB',(40,40)).save(base);original=base.read_bytes()
        result=self.run_cli(base,'--width',40,'--height',40,'--background',base)
        self.assertNotEqual(0,result.returncode);self.assertIn('paths must differ',result.stderr);self.assertEqual(original,base.read_bytes())
if __name__=='__main__':unittest.main()
