package org.openstack.atlas.util.snmp;

import org.junit.Assert;
import org.apache.log4j.BasicConfigurator;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.openstack.atlas.util.snmp.exceptions.StingraySnmpGeneralException;
import org.openstack.atlas.util.snmp.exceptions.StingraySnmpRetryExceededException;
import org.openstack.atlas.util.snmp.exceptions.StingraySnmpSetupException;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.VariableBinding;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.openstack.atlas.util.snmp.StingraySnmpClient.getOidFromVirtualServerName;
import static org.openstack.atlas.util.snmp.StingraySnmpClient.getVirtualServerNameFromOid;

public class StingraySnmpClientTest {

    public String baseOid;
    public StingraySnmpClient client;
    public String address;
    public String port;
    public String community;
    public Map<String, String> knownOidMaps;

    public StingraySnmpClientTest() {
    }

    @Before
    public void setUp() {
        BasicConfigurator.configure();
        address = "10.12.99.19";
        port = "1161";
        community = "public";
        client = new StingraySnmpClient();
        client.setMaxRetrys(1);
        client.setAddress(address);
        client.setCommunity(community);
        client.setPort("1161");
        baseOid = "1.3.6.1.4.1.7146.1.2.2.2.1.9";
        knownOidMaps = new HashMap<String, String>();
        knownOidMaps.put("1.3.6.1.4.1.7146.1.2.2.2.1.9.8.77.121.115.113.108.95.86.83", "Mysql_VS");
        knownOidMaps.put("1.3.6.1.4.1.7146.1.2.2.2.1.9.15.108.98.97.97.115.95.97.100.109.105.110.95.97.112.105", "lbaas_admin_api");
        knownOidMaps.put("1.3.6.1.4.1.7146.1.2.2.2.1.9.16.108.98.97.97.115.95.112.117.98.108.105.99.95.97.112.105", "lbaas_public_api");
        baseOid = "1.3.6.1.4.1.7146.1.2.2.2.1.9";
    }

    @Test
    public void shouldReturnVariableBindingList() throws Exception {
        assertTrue(client.getBulkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS).size() > 0);
    }

    @Test
    public void shouldReturnMapOfStringByRawUsage() throws Exception {
        Map<String, RawSnmpUsage> map = client.getSnmpUsage();
        assertTrue(map.entrySet().size() > 0);
    }

    public void shouldFailWithInvalidAddress() throws Exception {
        client.setAddress("10.1000.1.1");
        try {
            client.getBulkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS);
        } catch (Exception ex) {
            assertTrue(ex instanceof StingraySnmpGeneralException);
        }
        Assert.fail();
    }

    @Test
    public void shouldFailWithIncorrectPort() throws Exception {
        client.setPort("1111");
        try {
            client.getBulkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS);
        } catch (Exception ex) {
            assertTrue(ex instanceof StingraySnmpGeneralException);
            return;
        }
        Assert.fail();
    }

    @Test
    public void shouldFailWithInvalidCommunity() throws Exception {
        client.setCommunity("expensivePradaBag");
        try {
            client.getBulkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS);
        } catch (Exception ex) {
            assertTrue(ex instanceof StingraySnmpGeneralException);
            return;
        }
        Assert.fail();
    }

    @Test
    public void testVsNameFromOid() {
        for (Entry<String, String> ent : knownOidMaps.entrySet()) {
            String oid = ent.getKey();
            String vsName = ent.getValue();
            assertEquals(vsName, getVirtualServerNameFromOid(baseOid, oid));
        }
    }

    @Test
    public void testOidFromVsName() {
        for (Entry<String, String> ent : knownOidMaps.entrySet()) {
            String oid = ent.getKey();
            String vsName = ent.getValue();

            assertEquals(oid, getOidFromVirtualServerName(baseOid, vsName));
        }
    }

    @Test
    public void testSingleVsByteCountRequest() throws Exception {
        VariableBinding variableBinding = client.getBulkOidBindingList(OIDConstants.VS_BYTES_OUT).get(0);
        String name = getVirtualServerNameFromOid(baseOid, variableBinding.getOid().toString());
        Long value = client.getLongValueForVirtualServer(name, OIDConstants.VS_BYTES_OUT);
        assertTrue(value >= 0);
        variableBinding = client.getBulkOidBindingList(OIDConstants.VS_BYTES_IN).get(0);
        name = getVirtualServerNameFromOid(baseOid, variableBinding.getOid().toString());
        value = client.getLongValueForVirtualServer(name, OIDConstants.VS_BYTES_IN);
        assertTrue(value >= 0);
        variableBinding = client.getBulkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS).get(0);
        name = getVirtualServerNameFromOid(baseOid, variableBinding.getOid().toString());
        value = client.getLongValueForVirtualServer(name, OIDConstants.VS_CURRENT_CONNECTIONS);
        assertTrue(value >= 0);
    }

    @Test
    public void testThreadRequestsAgainstAllStagingHosts() throws InterruptedException {
        final String ipAddress1 = "10.12.99.19"; // This is staging node n01
        final String ipAddress2 = "10.12.99.20"; // This is staging node n02
        final String ipAddress3 = "10.12.99.21"; // This is staging node n03
        final String ipAddress4 = "10.12.99.22"; // This is staging node n04
        final String baseOid = "1.3.6.1.4.1.7146.1.2.2.2.1.9";
        Runnable run1 = new Runnable() {

            public void run() {
                client.setAddress(ipAddress1);
                System.out.println("First run! address: " + ipAddress1);
                try {
                    client.getWalkOidBindingList(baseOid);
                } catch (Exception e) {
                }
            }
        };
        Runnable run2 = new Runnable() {

            public void run() {
                client.setAddress(ipAddress2);
                System.out.println("Second run! address: " + ipAddress2);
                try {
                    client.getWalkOidBindingList(baseOid);
                } catch (Exception e) {
                }
            }
        };
        Runnable run3 = new Runnable() {

            public void run() {
                client.setAddress(ipAddress3);
                System.out.println("Third run! address: " + ipAddress3);
                try {
                    client.getWalkOidBindingList(baseOid);
                } catch (Exception e) {
                }
            }
        };
        Runnable run4 = new Runnable() {

            public void run() {
                client.setAddress(ipAddress4);
                System.out.println("Fourth run! address: " + ipAddress4);
                try {
                    client.getWalkOidBindingList(baseOid);
                } catch (Exception e) {
                }
            }
        };
        Thread thread1 = new Thread(run1);
        Thread thread2 = new Thread(run2);
        Thread thread3 = new Thread(run3);
        Thread thread4 = new Thread(run4);

        thread1.start();
        thread2.start();
        thread3.start();
        thread4.start();

        thread1.join();
        thread2.join();
        thread3.join();
        thread4.join();
        try {
            Thread.currentThread().sleep(1000);
        } catch (InterruptedException ie) {
        }
    }
}