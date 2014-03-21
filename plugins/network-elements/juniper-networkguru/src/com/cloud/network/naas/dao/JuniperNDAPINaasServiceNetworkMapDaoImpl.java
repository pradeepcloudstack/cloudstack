package com.cloud.network.naas.dao;

import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.ejb.Local;
import javax.inject.Inject;

import org.springframework.stereotype.Component;




import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

import com.cloud.utils.db.*;
import com.cloud.network.dao.NetworkOpVO;
import com.cloud.network.dao.NetworkVO;

import com.cloud.network.naas.dao.JuniperNDAPINaasServiceNetworkMapDao;
import com.cloud.network.JuniperNDAPINaasServiceNetworkMapVO;

@Component
@Local(value={JuniperNDAPINaasServiceNetworkMapDao.class})
@DB(txn = false)
public class JuniperNDAPINaasServiceNetworkMapDaoImpl extends GenericDaoBase<JuniperNDAPINaasServiceNetworkMapVO, Long> implements JuniperNDAPINaasServiceNetworkMapDao{
	
	
	  protected SearchBuilder<JuniperNDAPINaasServiceNetworkMapVO> AllFieldsSearch;
	  
	  public JuniperNDAPINaasServiceNetworkMapDaoImpl() {
		// TODO Auto-generated constructor stub
	}

	  @PostConstruct
	    protected void init() {
		  
		  AllFieldsSearch = createSearchBuilder();
		  
		  AllFieldsSearch.and("naasId", AllFieldsSearch.entity().getNaasId(), SearchCriteria.Op.EQ);
	      AllFieldsSearch.and("guestNetworkId", AllFieldsSearch.entity().getGuestNetworkId(), SearchCriteria.Op.EQ);
	      AllFieldsSearch.done();
		  
	  }
	  	    
	    @Override
	    public JuniperNDAPINaasServiceNetworkMapVO findOneByNaasId(long naasId) {
	        SearchCriteria<JuniperNDAPINaasServiceNetworkMapVO> sc = AllFieldsSearch.create();
	        sc.setParameters("naasId", naasId);
	        return findOneBy(sc);
	    }
	    
	    @Override
	    public JuniperNDAPINaasServiceNetworkMapVO findOneByNetworkId(
	    		long guestNetworkId) {
	        SearchCriteria<JuniperNDAPINaasServiceNetworkMapVO> sc = AllFieldsSearch.create();
	        sc.setParameters("guestNetworkId", guestNetworkId);
	        return findOneBy(sc);
	    }
	    
	    @Override
	    @DB
	    public JuniperNDAPINaasServiceNetworkMapVO persist(JuniperNDAPINaasServiceNetworkMapVO naasServiceNetwork) {
	        Transaction txn = Transaction.currentTxn();
	        txn.start();

	        // 1) create network
	        JuniperNDAPINaasServiceNetworkMapVO newNaasServiceNetwork = super.persist(naasServiceNetwork);
	   

	        txn.commit();
	        return newNaasServiceNetwork;
	    }

		@Override
		public boolean deleteNaasServiceNetworkMapByNeworkId(long networkId) {			
			 SearchCriteria<JuniperNDAPINaasServiceNetworkMapVO> sc = AllFieldsSearch.create();
		     sc.setParameters("guestNetworkId", networkId);		
		     return remove(sc) > 0;
		}
}
