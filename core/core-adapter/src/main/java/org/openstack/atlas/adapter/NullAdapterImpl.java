package org.openstack.atlas.adapter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.adapter.exception.AdapterException;

import org.openstack.atlas.service.domain.entity.LoadBalancerProtocol;
import org.openstack.atlas.service.domain.entity.LoadBalancerAlgorithm;

import org.openstack.atlas.core.api.v1.LoadBalancer;
import org.openstack.atlas.core.api.v1.Node;
import org.openstack.atlas.core.api.v1.ConnectionLogging;
import org.openstack.atlas.core.api.v1.ConnectionThrottle;
import org.openstack.atlas.core.api.v1.HealthMonitor;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;
import java.util.Map;

@Service
public class NullAdapterImpl implements LoadBalancerAdapter {
    public static Log LOG = LogFactory.getLog(NullAdapterImpl.class.getName());
    private static String SOURCE_IP = "SOURCE_IP";
    private static String HTTP_COOKIE = "HTTP_COOKIE";
    private static LoadBalancerAlgorithm DEFAULT_ALGORITHM = LoadBalancerAlgorithm.ROUND_ROBIN;
    
    @Override
    public void createLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, LoadBalancer lb) throws AdapterException {
        LOG.info("createLoadBalancer"); // NOP
    }

    @Override
    public void updateLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, LoadBalancer lb) throws AdapterException {
        LOG.info("updateLoadBalancer");// NOP
    }

    @Override
    public void deleteLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException {
        LOG.info("deleteLoadBalancer");// NOP
    }

    @Override
    public void createNodes(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Set<Node> nodes) throws AdapterException {
        LOG.info("createNodes");// NOP
    }

    @Override
    public void deleteNodes(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Set<Integer> nodeIds) throws AdapterException {
        LOG.info("deleteNodes");// NOP
    }

    @Override
    public void updateNode(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Node node) throws AdapterException {
        LOG.info("updateNodes");// NOP
    }
 
    @Override
    public void deleteNode(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Integer nodeId) throws AdapterException {
        LOG.info("deleteNode");// NOP
    }

    @Override
    public void updateConnectionLogging(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, ConnectionLogging conLog) throws AdapterException {
        LOG.info("updateConnectionLogging");// NOP
    }

    @Override
    public void updateConnectionThrottle(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, ConnectionThrottle conThrottle) throws AdapterException {
        LOG.info("updateConnectionThrottle");// NOP
    }

    @Override
    public void deleteConnectionThrottle(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException {
        LOG.info("deleteConnectionThrottle");// NOP
    }

    @Override
    public void updateHealthMonitor(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, HealthMonitor monitor) throws AdapterException {
        LOG.info("updateHealthMonitor");// NOP
    }

    @Override
    public void deleteHealthMonitor(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException {
        LOG.info("deleteHealthMonitor");// NOP
    }

}
