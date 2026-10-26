"""协议与资源释放测试，不读取真实摄像头；真实摄像头需用户本机复测。"""
import http.client
import io
import json
import threading
import unittest
from unittest.mock import patch

from camera_server import FrameHub,make_server


class CameraServerTests(unittest.TestCase):
    code='test-camera-code-2026'
    def setUp(self):
        self.hub=FrameHub(test_pattern=True,fps=10)
        self.hub.start()
        self.server=make_server(self.hub,'127.0.0.1',0,self.code)
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()
        self.client=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
    def tearDown(self):
        self.client.close(); self.server.shutdown(); self.server.server_close(); self.hub.close(); self.thread.join(2)
    def get(self,path,code=None):
        self.client.request('GET',path,headers={'Authorization':'Bearer '+(code or self.code)})
        return self.client.getresponse()
    def test_wrong_access_code_is_rejected(self):
        response=self.get('/video.mjpeg','wrong-code')
        self.assertEqual(401,response.status); self.assertFalse(json.loads(response.read())['ok'])
    def test_health_explicitly_marks_synthetic_source(self):
        response=self.get('/health')
        data=json.loads(response.read()); self.assertEqual('test_pattern',data['source']); self.assertGreater(data['sequence'],0)
    def test_authenticated_stream_has_bounded_jpeg_part(self):
        response=self.get('/video.mjpeg')
        self.assertEqual(200,response.status)
        self.assertIn('boundary=frame',response.getheader('Content-Type'))
        self.assertEqual(b'--frame\r\n',response.readline())
        headers={}
        while True:
            line=response.readline()
            if line==b'\r\n': break
            key,value=line.decode().split(':',1); headers[key.lower()]=value.strip()
        size=int(headers['content-length'])
        self.assertTrue(100<size<=2*1024*1024)
        frame=response.read(size)
        self.assertTrue(frame.startswith(b'\xff\xd8') and frame.endswith(b'\xff\xd9'))
        import cv2
        import numpy as np
        image=cv2.imdecode(np.frombuffer(frame,dtype=np.uint8),cv2.IMREAD_COLOR)
        self.assertEqual((480,640),image.shape[:2])
    def test_unknown_path_does_not_expose_frames(self):
        response=self.get('/video.mjpeg?code=bad')
        self.assertEqual(404,response.status); response.read()
    def test_short_code_is_not_accepted(self):
        with self.assertRaises(ValueError): make_server(self.hub,'127.0.0.1',0,'short')
    def test_pattern_does_not_open_hardware(self):
        import cv2
        with patch.object(cv2,'VideoCapture',side_effect=AssertionError('合成图不得访问摄像头')):
            other=FrameHub(test_pattern=True)
            try: other.start(); self.assertGreater(other.sequence,0)
            finally: other.close()
    def test_camera_capture_is_released_on_close(self):
        import cv2
        import numpy as np
        class FakeCapture:
            released=False
            def isOpened(self): return True
            def set(self,*args): return True
            def read(self): return True,np.zeros((480,640,3),dtype=np.uint8)
            def release(self): self.released=True
        capture=FakeCapture()
        with patch.object(cv2,'VideoCapture',return_value=capture):
            other=FrameHub()
            try: other.start()
            finally: other.close()
        self.assertTrue(capture.released)

if __name__=='__main__': unittest.main(verbosity=2)
