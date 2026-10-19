"""局域网摄像头测试组件：只传输JPEG画面，不录音录像，不连接飞控服务。"""
import argparse
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT=Path(__file__).resolve().parent
# 依赖安装在组件本地，避免修改无人机动力学Python环境。
sys.path.insert(0,os.environ.get('DRONE_CAMERA_DEPS',str(ROOT/'.deps')))


class FrameHub:
    def __init__(self,camera=0,width=640,height=480,fps=15,test_pattern=False):
        self.camera,self.width,self.height,self.fps=camera,width,height,fps
        self.test_pattern=test_pattern
        self.condition=threading.Condition()
        self.stopping=threading.Event()
        self.sequence=0
        self.jpeg=None
        self.error=None
        self.thread=threading.Thread(target=self.run,name='camera-capture',daemon=True)

    def start(self):
        self.thread.start()
        self.wait(0,8)

    def run(self):
        capture=None
        try:
            import cv2
            import numpy as np
            if not self.test_pattern:
                capture=cv2.VideoCapture(self.camera,cv2.CAP_DSHOW if os.name=='nt' else cv2.CAP_ANY)
                if not capture.isOpened(): raise RuntimeError('摄像头无法开启：检查Windows相机权限、设备编号及其他程序占用')
                capture.set(cv2.CAP_PROP_FRAME_WIDTH,self.width)
                capture.set(cv2.CAP_PROP_FRAME_HEIGHT,self.height)
                capture.set(cv2.CAP_PROP_FPS,self.fps)
            while not self.stopping.is_set():
                started=time.monotonic()
                if self.test_pattern:
                    # 明确标记合成测试图，不冒充真实摄像头或UE场景。
                    image=np.zeros((self.height,self.width,3),dtype=np.uint8)
                    image[:]=(48,32,16)
                    x=int((self.sequence*6)%(self.width-60))+30
                    cv2.circle(image,(x,self.height//2),24,(70,190,245),-1)
                    for label,y in [('TEST PATTERN - NOT CAMERA',40),('LEFT                        RIGHT',75)]:
                        cv2.putText(image,label,(12,y),cv2.FONT_HERSHEY_SIMPLEX,.65,(240,240,240),1,cv2.LINE_AA)
                    cv2.putText(image,str(self.sequence),(12,self.height-20),cv2.FONT_HERSHEY_SIMPLEX,.7,(100,240,100),2)
                else:
                    ok,image=capture.read()
                    if not ok: raise RuntimeError('摄像头已断开或读帧失败')
                    if image.shape[1]!=self.width or image.shape[0]!=self.height:
                        image=cv2.resize(image,(self.width,self.height))
                ok,encoded=cv2.imencode('.jpg',image,[cv2.IMWRITE_JPEG_QUALITY,75])
                if not ok: raise RuntimeError('JPEG编码失败')
                data=encoded.tobytes()
                if not 4<=len(data)<=2*1024*1024: raise RuntimeError('编码帧超出允许大小')
                with self.condition:
                    self.jpeg=data; self.sequence+=1; self.condition.notify_all()
                self.stopping.wait(max(0.,1/self.fps-(time.monotonic()-started)))
        except Exception as exc:
            with self.condition:
                self.error=str(exc); self.condition.notify_all()
        finally:
            if capture is not None: capture.release()

    def wait(self,after,timeout=2.):
        with self.condition:
            self.condition.wait_for(lambda:self.sequence>after or self.error or self.stopping.is_set(),timeout)
            if self.error: raise RuntimeError(self.error)
            if self.stopping.is_set() or self.sequence<=after: raise RuntimeError('摄像头画面超时或已关闭')
            return self.sequence,self.jpeg

    def close(self):
        self.stopping.set()
        with self.condition: self.condition.notify_all()
        if self.thread.ident: self.thread.join(3)


def make_server(hub,host,port,access_code):
    if len(access_code)<16 or not access_code.isascii() or any(c.isspace() for c in access_code):
        raise ValueError('摄像头访问码至少16位ASCII字符，不能有空格')
    slots=threading.BoundedSemaphore(4)
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def log_message(self,*args): pass # 不记录访问码、请求头或画面内容。
        def reply(self,status,value):
            body=json.dumps(value,ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close'); self.end_headers(); self.wfile.write(body)
            self.close_connection=True
        def do_GET(self):
            if not hmac.compare_digest(self.headers.get('Authorization','').encode(),('Bearer '+access_code).encode()):
                self.reply(401,{'ok':False,'error':'摄像头访问码错误'}); return
            if self.path=='/health':
                self.reply(200,{'ok':True,'source':'test_pattern' if hub.test_pattern else 'camera',
                    'sequence':hub.sequence,'error':hub.error}); return
            if self.path!='/video.mjpeg': self.reply(404,{'ok':False,'error':'未知画面地址'}); return
            if not slots.acquire(blocking=False): self.reply(503,{'ok':False,'error':'最多4个画面连接'}); return
            try:
                sequence,jpeg=hub.wait(0)
                self.send_response(200)
                self.send_header('Content-Type','multipart/x-mixed-replace; boundary=frame')
                self.send_header('Cache-Control','no-store'); self.send_header('Pragma','no-cache')
                self.send_header('Connection','close'); self.end_headers()
                while not hub.stopping.is_set():
                    self.wfile.write(b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: '+str(len(jpeg)).encode()+b'\r\n\r\n'+jpeg+b'\r\n')
                    self.wfile.flush()
                    sequence,jpeg=hub.wait(sequence)
            except (OSError,RuntimeError): pass
            finally: slots.release(); self.close_connection=True
    server=ThreadingHTTPServer((host,port),Handler)
    server.daemon_threads=True
    return server


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8768)
    parser.add_argument('--camera',type=int,default=0)
    parser.add_argument('--width',type=int,default=640)
    parser.add_argument('--height',type=int,default=480)
    parser.add_argument('--fps',type=int,default=15)
    parser.add_argument('--test-pattern',action='store_true')
    parser.add_argument('--access-code',default=None,help='不填则本次启动生成随机访问码')
    args=parser.parse_args()
    if not (0<=args.port<=65535 and 0<=args.camera<=15 and 160<=args.width<=1920 and 120<=args.height<=1080 and 1<=args.fps<=30):
        parser.error('端口、镜头编号、分辨率或帧率超出范围')
    hub=FrameHub(args.camera,args.width,args.height,args.fps,args.test_pattern)
    server=None
    try:
        hub.start()
        code=args.access_code or secrets.token_urlsafe(24)
        server=make_server(hub,args.host,args.port,code)
        print('摄像头测试服务已启动（不录音、不录像）',flush=True)
        print('手机画面地址：http://电脑IPv4:'+str(server.server_port)+'/video.mjpeg',flush=True)
        print('摄像头访问码：'+code,flush=True)
        print('电脑与手机连接同一局域网；按Ctrl+C关闭并释放摄像头。',flush=True)
        server.serve_forever(poll_interval=.1)
    except KeyboardInterrupt: pass
    except Exception as exc:
        print('启动失败：'+str(exc),file=sys.stderr)
        return 1
    finally:
        if server: server.server_close()
        hub.close()
    return 0

if __name__=='__main__': raise SystemExit(main())
