"""Offline generator regression tests; XML values are synthetic fixtures."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from xml.etree.ElementTree import fromstring

spec = importlib.util.spec_from_file_location('ev_profiles_generator', Path(__file__).with_name('build-ev-profiles.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)

class EpaConversionTest(unittest.TestCase):
    def lookup(self, consumption):
        replies = [
            '<menuItems><menuItem><value>Example</value></menuItem></menuItems>',
            '<menuItems><menuItem><value>EV</value></menuItem></menuItems>',
            '<menuItems><menuItem><value>123</value></menuItem></menuItems>',
            f'<vehicle><atvType>EV</atvType><combE>{consumption}</combE></vehicle>',
        ]
        with patch.object(generator, 'fetch_xml', side_effect=[fromstring(s) for s in replies]):
            return generator.epa_lookup(2026)

    def test_thirty_kwh_per_hundred_miles_is_186_wh_per_km(self):
        self.assertEqual(self.lookup('30'), {'Example|EV|2026': {'drivingWhPerKm': 186}})

    def test_nonfinite_and_invalid_consumption_is_ignored(self):
        for value in ('nan', 'inf', '-inf', '0', '-1', 'invalid', ''):
            with self.subTest(value=value):
                self.assertEqual(self.lookup(value), {})

    def test_other_efficiencies_preserve_dimensional_conversion(self):
        for value, expected in [('20', 124), ('40', 249), ('100', 621)]:
            with self.subTest(value=value):
                self.assertEqual(self.lookup(value)['Example|EV|2026']['drivingWhPerKm'], expected)

if __name__ == '__main__':
    unittest.main()
