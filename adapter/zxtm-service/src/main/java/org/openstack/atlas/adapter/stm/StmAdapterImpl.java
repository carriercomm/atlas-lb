package org.openstack.atlas.adapter.stm;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.adapter.LoadBalancerEndpointConfiguration;
import org.openstack.atlas.adapter.exceptions.InsufficientRequestException;
import org.openstack.atlas.adapter.exceptions.RollBackException;
import org.openstack.atlas.adapter.exceptions.StmRollBackException;
import org.openstack.atlas.adapter.helpers.CustomMappings;
import org.openstack.atlas.adapter.helpers.IpHelper;
import org.openstack.atlas.adapter.helpers.ResourceTranslator;
import org.openstack.atlas.adapter.helpers.ZxtmNameBuilder;
import org.openstack.atlas.adapter.service.ReverseProxyLoadBalancerStmAdapter;
import org.openstack.atlas.service.domain.entities.*;
import org.openstack.atlas.service.domain.pojos.Stats;
import org.openstack.atlas.service.domain.pojos.ZeusSslTermination;
import org.openstack.atlas.service.domain.util.Constants;
import org.openstack.atlas.service.domain.util.StringUtilities;
import org.rackspace.stingray.client.StingrayRestClient;
import org.rackspace.stingray.client.counters.VirtualServerStats;
import org.rackspace.stingray.client.exception.StingrayRestClientException;
import org.rackspace.stingray.client.exception.StingrayRestClientObjectNotFoundException;
import org.rackspace.stingray.client.pool.Pool;
import org.rackspace.stingray.client.ssl.keypair.Keypair;
import org.rackspace.stingray.client.traffic.ip.TrafficIp;
import org.rackspace.stingray.client.util.EnumFactory;
import org.rackspace.stingray.client.virtualserver.VirtualServer;
import org.rackspace.stingray.client.virtualserver.VirtualServerHttp;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.openstack.atlas.util.debug.Debug;

@Component
public class StmAdapterImpl implements ReverseProxyLoadBalancerStmAdapter {
    public static Log LOG = LogFactory.getLog(StmAdapterImpl.class.getName());
    private StmAdapterResources resources;

    /*
    Load Balancer Resources
     */
    public StmAdapterResources getResources() {
        if (resources == null)
            resources = new StmAdapterResources();
        return resources;
    }

    @Override
    public void createLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        String vsRedirectName = ZxtmNameBuilder.genRedirectVSName(loadBalancer);

        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);

        try {
            getResources().createPersistentClasses(config);

            if (loadBalancer.getHealthMonitor() != null && !loadBalancer.hasSsl()) {
                getResources().updateHealthMonitor(client, vsName, translator.getcMonitor());
            }

            if ((loadBalancer.getAccessLists() != null && !loadBalancer.getAccessLists().isEmpty())
                    || loadBalancer.getConnectionLimit() != null) {
                getResources().updateProtection(client, vsName, translator.getcProtection());
            }

            getResources().updateVirtualIps(client, vsName, translator.getcTrafficIpGroups());
            getResources().updatePool(client, vsName, translator.getcPool());
            getResources().updateVirtualServer(client, vsName, translator.getcVServer());
            if (loadBalancer.isHttpsRedirect() != null && loadBalancer.isHttpsRedirect()) {
                getResources().updateVirtualServer(client, vsRedirectName, translator.getcRedirectVServer());
            }
            client.destroy();
        } catch (Exception e) {
            LOG.error(String.format("Failed to create load balancer %s, rolling back...", loadBalancer.getId()));
            deleteLoadBalancer(config, loadBalancer);
            throw new StmRollBackException(String.format("Failed to create load balancer %s", loadBalancer.getId()), e);
        }
    }

    @Override
    public void updateLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, LoadBalancer queLb, UserPages up)
            throws InsufficientRequestException, StmRollBackException {

        StingrayRestClient client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        String vsRedirectName = ZxtmNameBuilder.genRedirectVSName(loadBalancer);

        try {

            translator.translateLoadBalancerResource(config, vsName, loadBalancer, queLb);

            if (queLb.getHealthMonitor() != null && !loadBalancer.hasSsl()) {
                getResources().updateHealthMonitor(client, vsName, translator.getcMonitor());
            }

            if ((queLb.getAccessLists() != null && !queLb.getAccessLists().isEmpty())
                    || queLb.getConnectionLimit() != null) {
                getResources().updateProtection(client, vsName, translator.getcProtection());
            }

            if ((queLb.getLoadBalancerJoinVip6Set() != null && !queLb.getLoadBalancerJoinVip6Set().isEmpty())
                    || (queLb.getLoadBalancerJoinVipSet() != null && !queLb.getLoadBalancerJoinVipSet().isEmpty())) {
                getResources().updateVirtualIps(client, vsName, translator.getcTrafficIpGroups());
            }


            UserPages userPages = queLb.getUserPages();
            if (userPages != null) {
                if (userPages.getErrorpage() != null) {
                    setErrorFile(config, loadBalancer, loadBalancer.getUserPages().getErrorpage());
                }
            }

            if (loadBalancer.isUsingSsl()) {
                //Handle SSLTerm https-redirect
                if (queLb.isHttpsRedirect() != null && queLb.isHttpsRedirect()) {
                    getResources().updateVirtualServer(client, vsName, translator.getcRedirectVServer());
                } else {
                    getResources().updatePool(client, vsName, translator.getcPool());
                    getResources().updateVirtualServer(client, vsName, translator.getcVServer());
                }

                //Handle SSLTerm LB
                String secureVsName = ZxtmNameBuilder.genSslVSName(loadBalancer);
                translator.translateLoadBalancerResource(config, secureVsName, loadBalancer, queLb);
                getResources().updateVirtualServer(client, secureVsName, translator.getcVServer());
            } else {
                //Handle non-SSLTerm (normal) LB
                getResources().updatePool(client, vsName, translator.getcPool());
                getResources().updateVirtualServer(client, vsName, translator.getcVServer());

                //Handle non-SSLTerm https-redirect
                if (queLb.isHttpsRedirect() != null && queLb.isHttpsRedirect()) {
                    getResources().updateVirtualServer(client, vsRedirectName, translator.getcRedirectVServer());
                } else {
                    getResources().deleteVirtualServer(client, vsRedirectName);
                    getResources().deletePool(client, vsRedirectName);
                }
            }

        } catch (Exception ex) {
            client.destroy();
            LOG.error("Exception updating load balancer: " + ex);
            throw new StmRollBackException("Failed to update loadbalancer", ex);
        }
        client.destroy();
    }

    @Override
    public void deleteLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        String vsRedirectName = ZxtmNameBuilder.genRedirectVSName(loadBalancer);
        String errorPageName = ZxtmNameBuilder.generateErrorPageName(vsName);

        LOG.debug(String.format("Removing loadbalancer: %s ...", vsName));
        getResources().deleteRateLimit(config, loadBalancer, vsName);
        getResources().deleteHealthMonitor(client, vsName);
        getResources().deleteProtection(client, vsName);
        deleteVirtualIps(config, loadBalancer);
        getResources().deletePool(client, vsName);
        getResources().deleteVirtualServer(client, vsName);
        if (loadBalancer.hasSsl()) {
            getResources().deleteVirtualServer(client, ZxtmNameBuilder.genSslVSName(loadBalancer));
        }
        if (loadBalancer.isHttpsRedirect() != null && loadBalancer.isHttpsRedirect()) {
            getResources().deleteVirtualServer(client, vsRedirectName);
        }
        try {
            client.deleteExtraFile(errorPageName);
        } catch (Exception ignoredException) {}
        client.destroy();
        LOG.debug(String.format("Successfully removed loadbalancer: %s from the STM service...", vsName));
    }

    /*
       Pool Resources
    */


    @Override
    public void setNodes(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws InsufficientRequestException, StmRollBackException {

        String poolName = ZxtmNameBuilder.genVSName(loadBalancer);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        translator.translatePoolResource(poolName, loadBalancer, loadBalancer);
        LOG.info(String.format("Setting nodes to pool '%s'", poolName));
        getResources().updatePool(client, poolName, translator.getcPool());
        LOG.info(String.format("Successfully added nodes to pool '%s'", poolName));
        client.destroy();
    }

    @Override
    public void removeNodes(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, List<Node> doomedNodes) throws InsufficientRequestException, StmRollBackException {
        String poolName = ZxtmNameBuilder.genVSName(loadBalancer);
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        Set<Node> currentNodes = loadBalancer.getNodes();
        Map<Integer, Node> nodesMap = new HashMap<Integer, Node>();

        for (Node currentNode : currentNodes) {
            nodesMap.put(currentNode.getId(), currentNode);
        }

        for (Node doomedNode : doomedNodes) {
            Integer id = doomedNode.getId();
            if (nodesMap.containsKey(id)) {
                currentNodes.remove(nodesMap.get(id));
            }
        }

        Pool cpool = getResources().getPool(client, poolName);
        cpool.getProperties().getBasic().getNodes().remove(doomedNodes.get(0).getIpAddress() + ":" + doomedNodes.get(0).getPort());
//        cpool.getProperties().getLoad_balancing().getNode_weighting().get(0)
        loadBalancer.setNodes(currentNodes);
        translator.translatePoolResource(poolName, loadBalancer, loadBalancer);

        LOG.info(String.format("Removing nodes from pool '%s'", poolName));
        getResources().updatePool(client, poolName, translator.getcPool());
        LOG.info(String.format("Successfully removed nodes from pool '%s'", poolName));
        client.destroy();
    }

    @Override
    public void removeNode(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, Node nodeToDelete) throws InsufficientRequestException, StmRollBackException {
        List<Node> doomedNodes = new ArrayList<Node>();
        doomedNodes.add(nodeToDelete);
        removeNodes(config, loadBalancer, doomedNodes);
    }


    /*
       VirtualIP Resources
    */
    @Override
    public void updateVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
        LOG.debug(String.format("Updating virtual ips for virtual server %s", vsName));
        getResources().updateVirtualIps(client, vsName, translator.getcTrafficIpGroups());
        LOG.debug(String.format("Updating virtual server %s for virtual ip configuration update", vsName));
        getResources().updateVirtualServer(client, vsName, translator.getcVServer());
        LOG.info(String.format("Successfully updated virtual ips for virtual server %s", vsName));
        client.destroy();
    }


    @Override
    public void deleteVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, List<Integer> vipIds, UserPages up) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        String vsName;
        vsName = ZxtmNameBuilder.genVSName(loadBalancer);

        //Lazy loading...
        loadBalancer.setUserPages(up);

        translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer, false);
        Map<String, TrafficIp> curTigMap = translator.getcTrafficIpGroups();

        Set<LoadBalancerJoinVip> jvipsToRemove = new HashSet<LoadBalancerJoinVip>();
        Set<LoadBalancerJoinVip6> jvips6ToRemove = new HashSet<LoadBalancerJoinVip6>();

        //Remove vips to remove from lb and translate...
        for (int id : vipIds) {
            for (LoadBalancerJoinVip jvip : loadBalancer.getLoadBalancerJoinVipSet()) {
                if (jvip.getVirtualIp().getId() == id) {
                    jvipsToRemove.add(jvip);
                }
            }

            for (LoadBalancerJoinVip6 jvip : loadBalancer.getLoadBalancerJoinVip6Set()) {
                if (jvip.getVirtualIp().getId() == id) {
                    jvips6ToRemove.add(jvip);
                }
            }
        }

        if (!jvipsToRemove.isEmpty()) loadBalancer.getLoadBalancerJoinVipSet().removeAll(jvipsToRemove);
        if (!jvips6ToRemove.isEmpty()) loadBalancer.getLoadBalancerJoinVip6Set().removeAll(jvips6ToRemove);
        translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer, false);
        // After translating, we need to add the vips back into the LB object so that the Listener can properly remove them from the DB
        if (!jvipsToRemove.isEmpty()) loadBalancer.getLoadBalancerJoinVipSet().addAll(jvipsToRemove);
        if (!jvips6ToRemove.isEmpty()) loadBalancer.getLoadBalancerJoinVip6Set().addAll(jvips6ToRemove);

        String vipsToRemove = StringUtilities.DelimitString(vipIds, ",");
        Map<String, TrafficIp> removeTigMap = translator.getcTrafficIpGroups();

        Set<String> tigsToRemove = new HashSet<String>(curTigMap.keySet());
        tigsToRemove.removeAll(removeTigMap.keySet());

        if (tigsToRemove.isEmpty()) {
            LOG.debug(String.format("Could not remove vip(s) %s for loadbalancer %s assuming vip is already deleted...", vipsToRemove, loadBalancer.getId()));
            return;
        }

        String tname = null;
        try {
            LOG.debug(String.format("Attempting to update traffic ip configuration and remove vips %s for virtual server %s", vipsToRemove, vsName));
            tname = null;
            for (String tigname : tigsToRemove) {
                tname = tigname;
                LOG.debug(String.format("Removing virtual ip %s...", tigname));
                client.deleteTrafficIp(tigname);
                LOG.info(String.format("Successfully removed virtual ip %s...", tigname));

            }
            LOG.debug(String.format("Updating virtual server %s for updated virtual ip configuration..", vsName));
            getResources().updateVirtualServer(client, vsName, translator.getcVServer());
            LOG.info(String.format("Successfully updated traffic ip configuration and removed vips %s for virtual server %s", vipsToRemove, vsName));
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.error(String.format("Object not found when removing virtual ip: %s for virtual server %s, continue...", tname, vsName));
        } catch (Exception ex) {
            String em = String.format("Error removing virtual ips for vs: %s ... \n Exception: %s",
                    vsName, ex);
            LOG.error(em);
            if (!curTigMap.isEmpty()) {
                LOG.debug(String.format("Attempting to roll back to previous virtual ips: %s for virtual server %s", vipsToRemove, vsName));
                getResources().updateVirtualIps(client, vsName, curTigMap);
                LOG.info(String.format("Successfully rolled back to previous virtual ips: %s for virtual server %s", vipsToRemove, vsName));
            }
            throw new StmRollBackException(em, ex);
        }
        client.destroy();
    }

    protected void deleteVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        List<Integer> vipIds = new ArrayList<Integer>();
        Set<LoadBalancerJoinVip> jvipset = loadBalancer.getLoadBalancerJoinVipSet();
        Set<LoadBalancerJoinVip6> jvip6set = loadBalancer.getLoadBalancerJoinVip6Set();

        for (LoadBalancerJoinVip jv : jvipset) {
            vipIds.add(jv.getVirtualIp().getId());
        }

        for (LoadBalancerJoinVip6 jv : jvip6set) {
            vipIds.add(jv.getVirtualIp().getId());
        }

        deleteVirtualIps(config, loadBalancer, vipIds, null);
    }

    /*
        Monitor Resources
     */

    @Override
    public void updateHealthMonitor(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws InsufficientRequestException, StmRollBackException {

        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        StingrayRestClient client = getResources().loadSTMRestClient(config);

        translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
        getResources().updateHealthMonitor(client, vsName, translator.getcMonitor());
        getResources().updatePool(client, vsName, translator.getcPool());
        client.destroy();
    }

    @Override
    public void deleteHealthMonitor(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        final String monitorName = ZxtmNameBuilder.genVSName(loadBalancer);
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        getResources().deleteHealthMonitor(client, monitorName);
        client.destroy();
    }

    /*
        Protection Resources
     */

    @Override
    public void updateProtection(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws StmRollBackException, InsufficientRequestException {
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String protectionName = ZxtmNameBuilder.genVSName(loadBalancer);
        LOG.info(String.format("Updating protection on %s...", protectionName));
        getResources().updateProtection(client, protectionName, translator.translateProtectionResource(loadBalancer));
        LOG.info(String.format("Successfully created protection %s", protectionName));
        client.destroy();
    }

    @Override
    public void deleteProtection(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {

        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String protectionName = ZxtmNameBuilder.genVSName(loadBalancer);
        LOG.info(String.format("Deleting protection on %s...", protectionName));
        getResources().deleteProtection(client, protectionName);
        LOG.info(String.format("Successfully deleted protection %s!", protectionName));
        client.destroy();
    }

    @Override
    public void updateAccessList(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        if (loadBalancer.getAccessLists() != null && !loadBalancer.getAccessLists().isEmpty()) {
            String name = ZxtmNameBuilder.genVSName(loadBalancer);
            LOG.info(String.format("Updating Access List on '%s'...", name));
            updateProtection(config, loadBalancer);
            LOG.info(String.format("Successfully updated Access List on '%s'...", name));
        }
    }

    @Override
    public void deleteAccessList(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, List<Integer> accessListToDelete)
            throws InsufficientRequestException, StmRollBackException {
        Set<AccessList> saveItems = new HashSet<AccessList>();
        for (AccessList item : loadBalancer.getAccessLists()) {
            if (!accessListToDelete.contains(item.getId())) {
                saveItems.add(item);
            }
        }
        loadBalancer.setAccessLists(saveItems);
        String name = ZxtmNameBuilder.genVSName(loadBalancer);
        LOG.info(String.format("Deleting Access List on '%s'...", name));
        if (loadBalancer.getConnectionLimit() != null || !loadBalancer.getAccessLists().isEmpty()) {
            updateProtection(config, loadBalancer);
        } else {
            deleteProtection(config, loadBalancer);
        }
        LOG.info(String.format("Successfully deleted Access List on '%s'.", name));
    }

    @Override
    public void updateConnectionThrottle(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        if (loadBalancer.getConnectionLimit() != null) {
            String name = ZxtmNameBuilder.genVSName(loadBalancer);
            LOG.info(String.format("Updating Connection Throttling on '%s'...", name));
            updateProtection(config, loadBalancer);
            LOG.info(String.format("Successfully updated Connection Throttling on '%s'.", name));
        }
    }

    @Override
    public void deleteConnectionThrottle(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        String name = ZxtmNameBuilder.genVSName(loadBalancer);

        LOG.info(String.format("Deleting Connection Throttling on '%s'...", name));
        if (loadBalancer.getAccessLists() != null && !loadBalancer.getAccessLists().isEmpty()) {
            ConnectionLimit nullConnectionLimit = new ConnectionLimit();
            nullConnectionLimit.setRateInterval(1);
            nullConnectionLimit.setMaxConnections(0);
            nullConnectionLimit.setMinConnections(0);
            nullConnectionLimit.setMaxConnectionRate(0);
            loadBalancer.setConnectionLimit(nullConnectionLimit);
            updateProtection(config, loadBalancer);
        } else {
            deleteProtection(config, loadBalancer);
        }
        LOG.info(String.format("Successfully deleted Connection Throttling on '%s'.", name));
    }

    /*
   SSL Termination Resources
    */

    @Override
    public void updateSslTermination(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, ZeusSslTermination sslTermination, UserPages up) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        String sslVsName = ZxtmNameBuilder.genSslVSName(loadBalancer);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        loadBalancer.setUserPages(up);
        translator.translateVirtualServerResource(config, sslVsName, loadBalancer);
        translator.translateKeypairResource(loadBalancer, true);
        VirtualServer createdServer = translator.getcVServer();
        VirtualServerHttp http = new VirtualServerHttp();
        http.setLocation_rewrite(EnumFactory.Accept_from.NEVER.toString());
        createdServer.getProperties().setHttp(http);
        if (loadBalancer.isSecureOnly()) {
            VirtualServer virtualServer;
            try {
                virtualServer = client.getVirtualServer(vsName);
            } catch (Exception e) {
                LOG.error(String.format("Error retrieving non-secure virtual server.\n%s", Debug.getExtendedStackTrace(e)));
                throw new StmRollBackException("Error retrieving non-secure virtual server.", e);
            }
            if (virtualServer != null) {
                virtualServer.getProperties().getBasic().setEnabled(false);
                getResources().updateVirtualServer(client, vsName, virtualServer);
            }
        }

        Keypair keypair = translator.getcKeypair();
        LOG.info(String.format("Updating certificate for load balancer: %s", loadBalancer.getId()));
        getResources().updateKeypair(client, sslVsName, keypair);

        try {
            translator.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
            translator.translateLoadBalancerResource(config, sslVsName, loadBalancer, loadBalancer);

            if ((loadBalancer.getAccessLists() != null && !loadBalancer.getAccessLists().isEmpty())
                    || loadBalancer.getConnectionLimit() != null) {
                getResources().updateProtection(client, vsName, translator.getcProtection());
            }

            getResources().updateVirtualIps(client, sslVsName, translator.getcTrafficIpGroups());
            getResources().updateVirtualServer(client, sslVsName, createdServer);

        } catch (Exception ex) {
            LOG.error(ex);
            throw new StmRollBackException("Failed to update loadbalancer, rolling back...", ex);
        }
        client.destroy();
    }

    @Override
    public void removeSslTermination(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        String vsName = ZxtmNameBuilder.genSslVSName(loadBalancer);

        LOG.debug(String.format("Removing ssl from loadbalancer: %s ...", vsName));
        getResources().deleteKeypair(client, vsName);
        getResources().deleteVirtualServer(client, vsName);
        LOG.debug(String.format("Successfully removed ssl from loadbalancer: %s from the STM service...", vsName));
        client.destroy();
    }

    @Override
    public void addSuspension(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        String vsName = ZxtmNameBuilder.genVSName(lb);
        try {
            LOG.info(String.format("Attempting to disable virtual server %s", vsName));
            VirtualServer virtualServer = client.getVirtualServer(vsName);
            virtualServer.getProperties().getBasic().setEnabled(false);
            getResources().updateVirtualServer(client, vsName, virtualServer);
            LOG.info(String.format("Successfully disabled virtual server %s", vsName));
            if (lb.hasSsl()) {
                LOG.info(String.format("Attempting to disable virtual server %s", vsName));
                vsName = ZxtmNameBuilder.genSslVSName(lb);
                VirtualServer secureServer = client.getVirtualServer(vsName);
                secureServer.getProperties().getBasic().setEnabled(false);
                getResources().updateVirtualServer(client, vsName, secureServer);
                LOG.info(String.format("Successfully disabled virtual server %s", vsName));
            }
            LOG.info(String.format("Attempting to disable Traffic Ip Groups"));
            translator.translateTrafficIpGroupsResource(config, lb, false);
            getResources().updateVirtualIps(client, vsName, translator.getcTrafficIpGroups());
            LOG.info(String.format("Traffic Ip Groups disabled"));
            LOG.info("Successfully added load balancer suspension");
        } catch (Exception e) {
            LOG.error(String.format("Failed to suspend load balancer operation for load balancer: %s Exception: %s", lb.getId(), e));
            throw new StmRollBackException("Failed to suspend loadbalancer", e);
        }
        client.destroy();
    }

    @Override
    public void removeSuspension(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws InsufficientRequestException, RollBackException {
        StingrayRestClient client;
        client = getResources().loadSTMRestClient(config);
        ResourceTranslator translator = ResourceTranslator.getNewResourceTranslator();
        String vsName = ZxtmNameBuilder.genVSName(lb);
        try {
            LOG.info(String.format("Attempting to enable virtual server %s", vsName));
            VirtualServer virtualServer = client.getVirtualServer(vsName);
            virtualServer.getProperties().getBasic().setEnabled(true);
            getResources().updateVirtualServer(client, vsName, virtualServer);
            LOG.info(String.format("Successfully enabled virtual server %s", vsName));
            if (lb.hasSsl()) {
                LOG.info(String.format("Attempting to enable virtual server %s", vsName));
                vsName = ZxtmNameBuilder.genSslVSName(lb);
                VirtualServer secureServer = client.getVirtualServer(vsName);
                secureServer.getProperties().getBasic().setEnabled(true);
                getResources().updateVirtualServer(client, vsName, secureServer);
                LOG.info(String.format("Successfully enabled virtual server %s", vsName));
            }
            LOG.info(String.format("Attempting to enable Traffic Ip Groups"));
            translator.translateTrafficIpGroupsResource(config, lb, true);
            getResources().updateVirtualIps(client, vsName, translator.getcTrafficIpGroups());
            LOG.info(String.format("Successfully enabled Traffic Ip Groups"));
            LOG.info("Successfully removed load balancer suspension");
        } catch (Exception e) {
            LOG.error(String.format("Failed to remove load balancer suspension for load balancer: %s. Exception: %s", lb.getId(), e));
            throw new StmRollBackException("Failed to remove loadbalancer suspension ", e);
        }
        client.destroy();
    }

    @Override
    public boolean isEndPointWorking(LoadBalancerEndpointConfiguration config) throws StmRollBackException {
        try {
            StingrayRestClient client = getResources().loadSTMRestClient(config);
            client.getPools();
            client.destroy();
            return true;
        } catch (Exception e) {
            if (IpHelper.isNetworkConnectionException(e)) {
                return false;
            }
            throw new StmRollBackException("Failed verifying endpoint", e);
        }
    }

    @Override
    public void changeHostForLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, Host newHost) throws InsufficientRequestException, RollBackException {

    }

    /*
    Rate Limit (Bandwidth) Resources
     */

    @Override
    public void setRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, RateLimit rateLimit) throws InsufficientRequestException, StmRollBackException {
        if (rateLimit != null) {
            loadBalancer.setRateLimit(rateLimit);
            getResources().setRateLimit(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer));
        } else {
            throw new StmRollBackException("Empty or null Rate Limit.  Roll back...");
        }
    }

    @Override
    public void updateRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, RateLimit rateLimit) throws InsufficientRequestException, StmRollBackException {
        if (rateLimit != null) {
            loadBalancer.setRateLimit(rateLimit);
            getResources().updateRateLimit(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer));
        } else {
            throw new StmRollBackException("Empty or null Rate Limit.  Roll back...");
        }
    }

    @Override
    public void deleteRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StmRollBackException {
        getResources().deleteRateLimit(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer));
    }

    /*
    Error File Resources
     */


    @Override
    public void uploadDefaultErrorFile(LoadBalancerEndpointConfiguration config, String content)
            throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        File errorFile = null;

        try {
            LOG.debug("Attempting to upload the default error file...");
            errorFile = getResources().getFileWithContent(content);
            client.createExtraFile(Constants.DEFAULT_ERRORFILE, errorFile);
            LOG.info("Successfully uploaded the default error file...");
        } catch (IOException e) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- IO exception", config.getEndpointUrl()));
        } catch (StingrayRestClientException ce) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- REST Client exception", config.getEndpointUrl()));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- Object not found", config.getEndpointUrl()));
        }

        if (errorFile != null) //noinspection ResultOfMethodCallIgnored
            errorFile.delete();
        client.destroy();
    }

    @Override
    public void deleteErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, UserPages up)
            throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        getResources().deleteErrorFile(config, client, loadBalancer, up);
        client.destroy();
    }

    @Override
    public void setErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String content) throws InsufficientRequestException, StmRollBackException {
        if (content != null && !(content.equals(""))) {
            StingrayRestClient client = getResources().loadSTMRestClient(config);
            getResources().setErrorFile(config, client, loadBalancer, content);
            client.destroy();
        } else {
            throw new StmRollBackException("No content provided for error page.  Roll back...");
        }
    }

    @Override
    public Stats getVirtualServerStats(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws InsufficientRequestException, StingrayRestClientObjectNotFoundException, StingrayRestClientException {
        StingrayRestClient client = getResources().loadSTMRestClient(config);
        List<VirtualServerStats> sslVirtualServerList = new ArrayList<VirtualServerStats>();
        List<VirtualServerStats> virtualServerList = new ArrayList<VirtualServerStats>();
        for (URI endpoint : config.getRestStatsEndpoints()) {
            if (loadBalancer.isUsingSsl()) {
                sslVirtualServerList.add(client.getVirtualServerStats(ZxtmNameBuilder.genSslVSName(loadBalancer), endpoint));
            }
            virtualServerList.add(client.getVirtualServerStats(ZxtmNameBuilder.genVSName(loadBalancer), endpoint));
        }
        Stats stats = CustomMappings.mapVirtualServerStatsLists(virtualServerList, sslVirtualServerList);
        return stats;
    }

    /**
     * Deprecating these(SubnetMapping calls) as per ops. Unused call that is difficult to test, may support in future if needed... *
     */

//    @Override
//    public void setSubnetMappings(LoadBalancerEndpointConfiguration config, Hostssubnet hostssubnet) throws StmRollBackException {
//        StingrayRestClient client;
//        try {
//            client = loadSTMRestClient(config);
//            List<Hostsubnet> subnetList = hostssubnet.getHostsubnets();
//
//            //Loop over Hosts ("dev1.lbaas.mysite.com", "dev2.lbaas.mysite.com", etc)
//            for (Hostsubnet hostsubnet : subnetList) {
//                String hsName = hostsubnet.getName();
//                TrafficManager trafficManager = client.getTrafficManager(hsName);
//                List<TrafficManagerTrafficIp> trafficManagerTrafficIpList = new ArrayList<TrafficManagerTrafficIp>();
//                List<NetInterface> interfaceList = hostsubnet.getNetInterfaces();
//
//                //Loop over interfaces (eth0, eth1, etc)
//                for (NetInterface netInterface : interfaceList) {
//                    List<Cidr> cidrList = netInterface.getCidrs();
//                    TrafficManagerTrafficIp trafficManagerTrafficIp = new TrafficManagerTrafficIp();
//                    Set<String> networkList = new HashSet<String>();
//
//                    // Loop over Cidr list which contains one subnet per Cidr
//                    for (Cidr cidr : cidrList) {
//                        networkList.add(cidr.getBlock());
//                    }
//
//                    trafficManagerTrafficIp.setName(netInterface.getName());
//                    trafficManagerTrafficIp.setNetworks(networkList);
//                    trafficManagerTrafficIpList.add(trafficManagerTrafficIp);
//                }
//                trafficManager.getProperties().getBasic().setTrafficip(trafficManagerTrafficIpList);
//                client.updateTrafficManager(hsName, trafficManager);
//            }
//        } catch (StingrayRestClientObjectNotFoundException e) {
//            throw new StmRollBackException("Failed updating subnet mappings", e);
//        } catch (StingrayRestClientException e) {
//            throw new StmRollBackException("Failed updating subnet mappings", e);
//        }
//    }
//
//    @Override
//    public void deleteSubnetMappings(LoadBalancerEndpointConfiguration config, Hostssubnet hostssubnet) throws StmRollBackException {
//        StingrayRestClient client;
//        try {
//            client = loadSTMRestClient(config);
//            List<Hostsubnet> subnetList = hostssubnet.getHostsubnets();
//
//            //Loop over Hosts ("dev1.lbaas.mysite.com", "dev2.lbaas.mysite.com", etc)
//            for (Hostsubnet hostsubnet : subnetList) {
//                String hsName = hostsubnet.getName();       // This name is of the form "dev1.lbaas.mysite.com"
//                TrafficManager trafficManager = client.getTrafficManager(hsName);
//                List<NetInterface> netInterfaceList = hostsubnet.getNetInterfaces();
//                //trafficManagerTrafficIpList is the current list of TrafficIPs for the host
//                List<TrafficManagerTrafficIp> trafficManagerTrafficIpList = trafficManager.getProperties().getBasic().getTrafficip();
//                Map<String, TrafficManagerTrafficIp> tipsMap = new HashMap<String, TrafficManagerTrafficIp>();
//
//                //Loop over tips to compile an indexed list by name
//                for (TrafficManagerTrafficIp trafficManagerTrafficIp : trafficManagerTrafficIpList) {
//                    tipsMap.put(trafficManagerTrafficIp.getName(), trafficManagerTrafficIp);
//                }
//
//                //Loop over interfaces (eth0, eth1, etc)
//                for (NetInterface netInterface : netInterfaceList) {
//                    String netInterfaceName = netInterface.getName(); //This name is of the form "eth0"
//
//                    if (tipsMap.containsKey(netInterfaceName)) {
//                        TrafficManagerTrafficIp tip = tipsMap.get(netInterfaceName);
//                        Set<String> networkSet = tip.getNetworks();
//                        List<Cidr> cidrList = netInterface.getCidrs(); //This is the list of objects containing subnet strings
//
//                        // Loop over Cidr list which contains one subnet per Cidr
//                        for (Cidr cidr : cidrList) {
//                            networkSet.remove(cidr.getBlock()); //Remove the subnet if it exists
//                        }
//                    }
//                }
//                client.updateTrafficManager(hsName, trafficManager);
//            }
//        } catch (StingrayRestClientObjectNotFoundException e) {
//            throw new StmRollBackException("Failed removing subnet mappings", e);
//        } catch (StingrayRestClientException e) {
//            throw new StmRollBackException("Failed removing subnet mappings", e);
//        }
//    }
//
//    @Override
//    public Hostssubnet getSubnetMappings(LoadBalancerEndpointConfiguration config, String host) throws StmRollBackException {
//        StingrayRestClient client;
//        Hostssubnet ret = new Hostssubnet();
//        try {
//            client = loadSTMRestClient(config);
//            TrafficManager trafficManager = client.getTrafficManager(host);
//            //trafficManagerTrafficIpList is the current list of TrafficIPs for the host
//            List<TrafficManagerTrafficIp> trafficManagerTrafficIpList = trafficManager.getProperties().getBasic().getTrafficip();
//            List<Hostsubnet> subnetList = new ArrayList<Hostsubnet>();
//            Hostsubnet hostsubnet = new Hostsubnet();
//            hostsubnet.setName(host);
//
//            //Loop over trafficIPs (== interfaces) (eth0, eth1, etc)
//            for (TrafficManagerTrafficIp trafficManagerTrafficIp : trafficManagerTrafficIpList) {
//                Set<String> networkSet = trafficManagerTrafficIp.getNetworks();
//                NetInterface netInterface = new NetInterface();
//                List<Cidr> cidrs = new ArrayList<Cidr>();
//
//                //Loop over networks (== cidr blocks)
//                for (String block : networkSet) {
//                    Cidr cidr = new Cidr();
//                    cidr.setBlock(block);
//                    cidrs.add(cidr);
//                }
//
//                netInterface.setName(trafficManagerTrafficIp.getName());
//                netInterface.setCidrs(cidrs);
//                hostsubnet.getNetInterfaces().add(netInterface);
//            }
//            subnetList.add(hostsubnet);
//            ret.setHostsubnets(subnetList);
//        } catch (StingrayRestClientObjectNotFoundException e) {
//            throw new StmRollBackException("Failed retrieving subnet mappings", e);
//        } catch (StingrayRestClientException e) {
//            throw new StmRollBackException("Failed retrieving subnet mappings", e);
//        }
//        return ret;
//    }
}
