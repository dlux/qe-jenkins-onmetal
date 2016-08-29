#!/usr/bin/env groovy

// Any command placed at the root of the file get executed during the load operation
echo "Loading external functions for an onMetal host..."


def wait_for_ping(host_ip, timeout_sec) {

    echo "Waiting for the host with IP:${host_ip} to become online."
    def response
    def current_time
    def initial_time = sh returnStdout: true, script: 'date +%s'
    waitUntil {
        current_time = sh returnStdout: true, script: 'date +%s'
        if (current_time.toInteger() - initial_time.toInteger() > timeout_sec.toInteger()) {
            error "The host did not respond to ping within the timeout of ${timeout} seconds"
        }
        response = sh returnStatus: true, script: "ping -q -c 1 ${host_ip}"
        return (response == 0)
    }

}


def onmetal_provision(datacenter_tag) {

    // Spin onMetal Server
    ansiblePlaybook playbook: 'build_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

    // Verify onMetal server data
    ansiblePlaybook inventory: 'hosts', playbook: 'get_onmetal_facts.yaml', sudoUser: null, tags: "${datacenter_tag}"

    // Get server IP address
    String hosts = readFile("hosts")
    String ip = hosts.substring(hosts.indexOf('=')+1)

    // Wait for server to become active
    wait_for_ping(ip, 600)

    // Prepare OnMetal server, retry up to 5 times for the command to work
    retry(5) {
        ansiblePlaybook inventory: 'hosts', playbook: 'prepare_onmetal.yaml', sudoUser: null
    }

    // Apply CPU fix - will restart server (~5 min)
    ansiblePlaybook inventory: 'hosts', playbook: 'set_onmetal_cpu.yaml', sudoUser: null

    // Wait for the server to come back online
    wait_for_ping(ip, 600)

    return (ip)

}


def vm_provision() {

    // Configure VMs onMetal server
    ansiblePlaybook inventory: 'hosts', playbook: 'configure_onmetal.yaml', sudoUser: null

    // Create VMs where OSA will be deployed
    ansiblePlaybook inventory: 'hosts', playbook: 'create_lab.yaml', sudoUser: null

}


def vm_preparation_for_osa() {

    // Prepare each VM for OSA installation
    ansiblePlaybook inventory: 'hosts', playbook: 'prepare_for_osa.yaml', sudoUser: null

}


def deploy_openstack() {
    
    ansiblePlaybook inventory: 'hosts', playbook: 'deploy_osa.yaml', sudoUser: null

}


def configure_tempest(host_ip) {

    // Install Tempest
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/openstack/tempest.git /root/tempest
    cd /root/tempest/
    sudo pip install -r requirements.txt
    cd /root/tempest/etc/
    wget https://raw.githubusercontent.com/CasJ/openstack_one_node_ci/master/tempest.conf
    '''
    """

    // Get the tempest config file generated by the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    scp infra01_utility:/opt/tempest_untagged/etc/tempest.conf /root/tempest/etc/tempest.conf.osa
    '''
    """

    // Configure tempest based on the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    keys="admin_password image_ref image_ref_alt uri uri_v3 public_network_id reseller_admin_role"
    for key in \$keys
    do
        a="\${key} ="
        sed -ir "s|\$a.*|\$a|g" /root/tempest/etc/tempest.conf
        b=`cat /root/tempest/etc/tempest.conf.osa | grep "\$a"`
        sed -ir "s|\$a|\$b|g" /root/tempest/etc/tempest.conf
    done
    '''
    """

}


def run_tempest_smoke_tests(host_ip) {

    // Run the tests and store the results in ~/subunit/before
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    mkdir /root/subunit
    cd /root/tempest/
    testr init
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke
    cp .testrepository/\$stream_id /root/subunit/before
    '''
    """

}


def delete_virtual_resources() {

    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_machines.yaml', sudoUser: null
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_networks.yaml', sudoUser: null
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_lab_state_file.yaml', sudoUser: null

}


def delete_onmetal(onmetal_ip, datacenter_tag) {

    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

    sh """
    ssh-keygen -R ${onmetal_ip}
    """

}


// The external code must return it's contents as an object
return this;

