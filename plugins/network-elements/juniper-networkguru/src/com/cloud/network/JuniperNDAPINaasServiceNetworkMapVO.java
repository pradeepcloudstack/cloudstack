package com.cloud.network;


import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.InternalIdentity;

@Entity
@Table(name = ("juniperNDAPI_naasService_Network_map"))
public class JuniperNDAPINaasServiceNetworkMapVO implements InternalIdentity{
	
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    
    @Column(name = "naas_id")
    private String naasId;
    
    
    @Column(name = "guestNetwork_id")
    private long guestNetworkId;
    
    @Column(name = "tenantId")
    private String tenantId;
    
    
    @Column(name = "naasDomainId")
    private String naasDomainId;
    
    @Column(name = "vlanId")
    private String vlanId;
    
    @Column(name = "l2csId")
    private String l2csId;
    
    public JuniperNDAPINaasServiceNetworkMapVO() {
		// TODO Auto-generated constructor stub
	}
        
    public JuniperNDAPINaasServiceNetworkMapVO(String tenantId,String naasDomainId,String naasId,String vlanId,String l2csId,long guestNetworkId) {
 		this.tenantId = tenantId;
 		this.naasDomainId = naasDomainId;
    	this.naasId = naasId;
    	this.vlanId = vlanId;
    	this.l2csId = l2csId;
 		this.guestNetworkId = guestNetworkId;
 	}
    
    public long getId() {
		return id;
	}
    
    public void setId(long id) {
		this.id = id;
	}
    
    public long getGuestNetworkId() {
		return guestNetworkId;
	}
    
    public void setGuestNetworkId(long guestNetworkId) {
		this.guestNetworkId = guestNetworkId;
	}
    
    public String getNaasId() {
		return naasId;
	}
    
    public void setNaasId(String naasId) {
		this.naasId = naasId;
	}
    
    public String getTenantId() {
		return tenantId;
	}
    
    public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}
    
    public String getNaasDomainId() {
		return naasDomainId;
	}
    
    public void setNaasDomainId(String naasDomainId) {
		this.naasDomainId = naasDomainId;
	}
    
    public String getVlanId() {
		return vlanId;
	}
    
    public void setVlanId(String vlanId) {
		this.vlanId = vlanId;
	}
    
    public String getL2csId() {
		return l2csId;
	}
    
    public void setL2csId(String l2csId) {
		this.l2csId = l2csId;
	}

}
