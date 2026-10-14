import unittest
import source_inventory

class InventoryTest(unittest.TestCase):
    def test_full_catalog_and_missing_sources_are_explicit(self):
        rows = list(source_inventory.rows())
        self.assertEqual(230, len(rows))
        self.assertEqual(200, sum(r['category'] == 'crypto' for r in rows))
        self.assertEqual(25, sum(r['category'] == 'forex' for r in rows))
        self.assertEqual({'USOIL', 'UKOIL', 'COPPER'}, {r['symbol'] for r in rows if r['keyless_history_candidates'] == 'NONE'})
        self.assertTrue(all(r['iran_network_status'].startswith('NOT_TESTED') for r in rows))
        self.assertTrue(all('MEXC:' in r['keyless_history_candidates'] for r in rows if r['category'] == 'crypto'))
