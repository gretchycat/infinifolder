#!/usr/bin/env python3
"""Exercise production and whole-checkout inventory scopes with disposable Git files."""
from pathlib import Path
import json,subprocess,sys,tempfile,unittest
SCRIPT=Path(__file__).with_name('inventory-repositories.py').resolve()
class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='infini-inventory-')
        self.root=Path(self.tmp.name)/'repo with spaces';self.root.mkdir()
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        for relative,data in {'root.sf3':b'bank','tests/bank.sf3':b'bank','src/main/assets/skin.wsz':b'skin','src/main/java/Source.kt':b'code','build/mistaken.bin':b'tracked output','untracked.sf3':b'private'}.items():
            p=self.root/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
        subprocess.run(['git','-C',str(self.root),'add','root.sf3','tests','src','build/mistaken.bin'],check=True)
        # The inventory records HEAD; create a local fixture commit with no hooks/signing.
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Inventory Test','-c','user.email=test@example.invalid','-c','commit.gpgsign=false','-c','core.hooksPath=/nonexistent','commit','-qm','fixture'],check=True)
    def tearDown(self):self.tmp.cleanup()
    def run_inventory(self,*options):
        return json.loads(subprocess.check_output([sys.executable,str(SCRIPT),*options,'test='+str(self.root)],text=True))
    def test_default_is_production_only(self):
        result=self.run_inventory()
        self.assertEqual(2,result['inventory'][0]['production_files'])
        self.assertFalse(result['inventory'][0]['tracked_worktree_changes'])
        self.assertFalse(result['exact_duplicate_resources'])
    def test_all_tracked_includes_root_and_test_bank(self):
        result=self.run_inventory('--all-tracked')
        self.assertEqual(5,result['inventory'][0]['tracked_files'])
        self.assertIn('build/mistaken.bin',[p['path'] for p in result['inventory'][0]['largest']])
        group=result['exact_duplicate_other_tracked_files'][0]
        self.assertEqual({'root.sf3','tests/bank.sf3'},{p['path'] for p in group})
        self.assertEqual(1,len({p['sha256'] for p in group}))
        self.assertNotIn('untracked.sf3',[p['path'] for p in result['inventory'][0]['largest']])
if __name__=='__main__':unittest.main()
