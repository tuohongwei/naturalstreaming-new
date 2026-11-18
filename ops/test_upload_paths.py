import io,tempfile,unittest,zipfile
from pathlib import Path
from upload_theme import read_directory_file,read_zip_entry

class InputBoundaryTest(unittest.TestCase):
 def test_rejects_drive_traversal_and_oversized_files(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'a.wav').write_bytes(b'12345')
   for path in ['../a.wav','C:/a.wav','/a.wav','a\\b.wav']:
    with self.assertRaises(ValueError):read_directory_file(root,path,10)
   with self.assertRaises(ValueError):read_directory_file(root,'a.wav',4)
   self.assertEqual(read_directory_file(root,'a.wav',5),b'12345')
 def test_rejects_file_symlink(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'actual').write_bytes(b'private')
   try:(root/'link').symlink_to(root/'actual')
   except OSError as e:self.skipTest(str(e))
   with self.assertRaises(ValueError):read_directory_file(root,'link',100)
 def test_zip_limit_checked_before_decompression(self):
  data=io.BytesIO()
  with zipfile.ZipFile(data,'w',zipfile.ZIP_DEFLATED) as z:z.writestr('a.wav',b'x'*10000)
  with zipfile.ZipFile(io.BytesIO(data.getvalue())) as z:
   with self.assertRaises(ValueError):read_zip_entry(z,'a.wav',100)

if __name__=='__main__':unittest.main()
