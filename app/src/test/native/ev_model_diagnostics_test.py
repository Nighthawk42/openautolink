"""Host executable EV diagnostics tests; no Android/Gradle dependency."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
CPP = ROOT / "app/src/main/cpp"


class DiagnosticsTest(unittest.TestCase):
    def test_constructed_payload_unchanged(self):
        # Frozen normalized construction from 6931356b, not regenerated from HEAD.
        import hashlib
        import re
        source = (CPP / 'jni_session.cpp').read_text()
        body = source.split('void JniSession::sendEnergyModelSensor', 1)[1]
        build = body[body.index('        aap_protobuf::service::sensorsource::message::SensorBatch batch;'):
                     body.index('        auto promise =')]
        # Diagnostics must be inserted AFTER promise creation to leave this fixture exact.
        build = re.sub(r'//[^\n]*', '', build)
        self.assertEqual(hashlib.sha256(' '.join(build.split()).encode()).hexdigest(),
                         '8c4bedce821419376e9ddf337e598cadecf7cb82ab96d6bd4f26617a436b674f')

    def test_diagnostic_wiring_only(self):
        source = (CPP / 'jni_session.cpp').read_text()
        body = source.split('void JniSession::sendEnergyModelSensor', 1)[1].split(
            'void JniSession::sendAccelerometerSensor', 1)[0]
        self.assertIn('vemMsg->SerializeToString(&serialized)', body)
        self.assertIn('std::weak_ptr<JniSession>', body)
        self.assertIn('outcome=sent', body)
        self.assertIn('outcome=failed', body)
        self.assertIn('outcome=queued', body)
        self.assertIn('coefficient_units=unresolved', body)
        self.assertIn('if (!streaming_ || !sensorChannel_)', body)
        self.assertIn('if (batteryCapacityWh <= 0 || batteryLevelWh <= 0 || rangeM <= 0)', body)
        self.assertEqual(body.count('sendSensorEventIndication(batch, std::move(promise))'), 1)
        # Promise callbacks run on strand; stop joins IO before deleting JNI refs.
        self.assertIn('if (!self || self->stopped_) return;', body)
        stop = source.split('void JniSession::stop()', 1)[1].split('void JniSession::nativeDiag', 1)[0]
        self.assertLess(stop.index('ioThread_.join()'), stop.index('DeleteGlobalRef(callbackRef_)'))
        dropped = source.split('void JniSession::logEnergyModelDiagOnce', 1)[1].split(
            'void JniSession::reportGalStartEnvelope', 1)[0]
        self.assertIn('if (!streaming_)', dropped)
        self.assertIn('LOGI(', dropped)
        self.assertLess(dropped.index('fetch_or'), dropped.index('ioService_->post'))

    def test_native_helper(self):
        self.assertTrue((CPP / "ev_model_diagnostics.h").exists(),
                        "bounded native diagnostics helper not implemented")
        compiler = shutil.which("g++")
        if not compiler:
            self.skipTest("g++ unavailable; run this test on build host")
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp) / "ev_diag_test"
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                            "-pthread", "-I", str(CPP), str(Path(__file__).with_suffix('.cpp')),
                            "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
