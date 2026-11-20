import importlib.util, pathlib, sys, time, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("dr_app",ROOT/"app.py")
app=importlib.util.module_from_spec(spec);sys.modules[spec.name]=app;spec.loader.exec_module(app)
class FeedTests(unittest.TestCase):
 def setUp(self):
  with app.LOCK:
   app.TICKS.clear();app.CANDLES.clear();app.EVENTS.clear();app.ANALYSES.clear();app.STATE.update(asset="WAITING",source="No verified browser feed")
 def test_accepts_real_tick(self):
  r=app.accept({"asset":"EURUSD_otc","price":1.08234,"timestamp":time.time()})
  self.assertTrue(r["accepted"]);self.assertEqual(app.status()["last_tick"]["price"],1.08234)
 def test_rejects_missing_asset(self):
  with self.assertRaisesRegex(ValueError,"asset"):app.accept({"price":1.2})
 def test_rejects_bad_prices(self):
  for p in (0,-1,float("nan"),float("inf"),"bad"):
   with self.subTest(p=p),self.assertRaises(ValueError):app.accept({"asset":"EURUSD_otc","price":p})
 def test_ohlc_aggregation(self):
  now=time.time();app.accept({"asset":"EURUSD_otc","price":1.1,"timestamp":now});app.accept({"asset":"EURUSD_otc","price":1.3,"timestamp":now+1})
  c=app.status()["candle"];self.assertEqual((c["open"],c["high"],c["low"],c["close"],c["ticks"]),(1.1,1.3,1.1,1.3,2))
 def test_quant_waits_without_candles(self):
  d=app.analyze();self.assertEqual(d["signal"],"WAIT");self.assertFalse(d["ready"])
 def test_quant_uses_closed_candles(self):
  start=int((time.time()-120)//10)*10
  for i in range(9):
   t=start+i*10+1;p=1.1+i*.0002;app.accept({"asset":"EURUSD_otc","price":p,"timestamp":t});app.accept({"asset":"EURUSD_otc","price":p+.00015,"timestamp":t+2})
  d=app.analyze();self.assertTrue(d["ready"]);self.assertIn(d["signal"],("CALL","PUT","WAIT"));self.assertEqual(d["candles_used"],8)
if __name__=="__main__":unittest.main(verbosity=2)
