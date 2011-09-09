package org.openstack.atlas.adapter;

import org.openstack.atlas.adapter.exception.AdapterException;
import org.openstack.atlas.core.api.v1.LoadBalancer;
import org.openstack.atlas.core.api.v1.Node;
import org.openstack.atlas.core.api.v1.ConnectionLogging;
import org.openstack.atlas.core.api.v1.ConnectionThrottle;
import org.openstack.atlas.core.api.v1.HealthMonitor;


import java.util.Collection;
import java.util.Set;
import java.util.Map;

public interface LoadBalancerAdapter {

    void createLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, LoadBalancer lb) throws AdapterException;

    void updateLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, LoadBalancer lb) throws AdapterException;

    void deleteLoadBalancer(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException;

    void createNodes(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Set<Node> nodes) throws AdapterException;

    void deleteNodes(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Set<Integer> nodeIds) throws AdapterException;

    void updateNode(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Node node) throws AdapterException;
 
    void deleteNode(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Integer nodeId) throws AdapterException;

    void updateConnectionLogging(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, ConnectionLogging conLog) throws AdapterException;

    void updateConnectionThrottle(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, ConnectionThrottle conThrottle) throws AdapterException;

    void deleteConnectionThrottle(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException;

    void updateHealthMonitor(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, HealthMonitor monitor) throws AdapterException;

    void deleteHealthMonitor(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException;
}
