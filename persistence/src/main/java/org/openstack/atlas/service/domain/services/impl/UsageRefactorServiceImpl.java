package org.openstack.atlas.service.domain.services.impl;

import org.openstack.atlas.service.domain.exceptions.EntityNotFoundException;
import org.openstack.atlas.service.domain.services.UsageRefactorService;
import org.openstack.atlas.service.domain.usage.entities.LoadBalancerHostUsage;
import org.openstack.atlas.service.domain.usage.entities.LoadBalancerMergedHostUsage;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class UsageRefactorServiceImpl extends BaseService implements UsageRefactorService {

    @Override
    public void createUsageEvent(LoadBalancerHostUsage loadBalancerHostUsage) {
        hostUsageRefactorRepository.create(loadBalancerHostUsage);
    }

    @Override
    public LoadBalancerHostUsage getLastRecordForLbIdAndHostId(int lbId, int hostId) {
        return hostUsageRefactorRepository.getMostRecentUsageRecordForLbIdAndHostId(lbId, hostId);
    }

    @Override
    public LoadBalancerMergedHostUsage getLastRecordForLbId(int lbId) throws EntityNotFoundException {
        return loadBalancerMergedHostUsageRepository.getMostRecentRecordForLoadBalancer(lbId);
    }

    @Override
    public Map<Integer, Map<Integer, List<LoadBalancerHostUsage>>> getAllLoadBalancerHostUsages() {
        List<LoadBalancerHostUsage> lbHostUsages = hostUsageRefactorRepository.getAllLoadBalancerHostUsageRecords(true);
        return listToMap(lbHostUsages);
    }

    @Override
    public Map<Integer, Map<Integer, List<LoadBalancerHostUsage>>> getRecordsAfterTime(Calendar time) {
        List<LoadBalancerHostUsage> lbHostUsages = hostUsageRefactorRepository.getRecordsAfterTime(time, true);
        LOG.info(String.format("Retrieved %d records after time: %s", lbHostUsages.size(), time.getTime().toString()));
        return listToMap(lbHostUsages);
    }

    @Override
    public void batchCreateLoadBalancerHostUsages(List<LoadBalancerHostUsage> usages) {
        hostUsageRefactorRepository.batchCreate(usages);
    }

    @Override
    public void deleteOldLoadBalancerHostUsages(Calendar deleteTimeMarker, Collection<Integer> lbsToExclude) {
        hostUsageRefactorRepository.deleteOldHostUsage(deleteTimeMarker, lbsToExclude);
    }

    @Override
    public void batchCreateLoadBalancerMergedHostUsages(List<LoadBalancerMergedHostUsage> usages) {
        loadBalancerMergedHostUsageRepository.batchCreate(usages);
    }

    @Override
    public void batchDeleteLoadBalancerMergedHostUsages(Collection<LoadBalancerMergedHostUsage> usages) {
        loadBalancerMergedHostUsageRepository.batchDelete(usages);
    }

    private Map<Integer, Map<Integer, List<LoadBalancerHostUsage>>> listToMap(List<LoadBalancerHostUsage> lbHostUsages) {
        Map<Integer, Map<Integer, List<LoadBalancerHostUsage>>> lbMap = new HashMap<Integer, Map<Integer, List<LoadBalancerHostUsage>>>();
        for (LoadBalancerHostUsage lbHostUsage : lbHostUsages) {
            if (!lbMap.containsKey(lbHostUsage.getLoadbalancerId())){
                lbMap.put(lbHostUsage.getLoadbalancerId(), new HashMap<Integer, List<LoadBalancerHostUsage>>());
            }
            if (!lbMap.get(lbHostUsage.getLoadbalancerId()).containsKey(lbHostUsage.getHostId())) {
                lbMap.get(lbHostUsage.getLoadbalancerId()).put(lbHostUsage.getHostId(), new ArrayList<LoadBalancerHostUsage>());
            }
            lbMap.get(lbHostUsage.getLoadbalancerId()).get(lbHostUsage.getHostId()).add(lbHostUsage);
        }
        return lbMap;
    }
}
