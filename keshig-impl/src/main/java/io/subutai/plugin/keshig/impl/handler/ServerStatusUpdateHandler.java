package io.subutai.plugin.keshig.impl.handler;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import io.subutai.common.command.CommandException;
import io.subutai.common.command.CommandResult;
import io.subutai.common.command.RequestBuilder;
import io.subutai.common.peer.ResourceHost;
import io.subutai.plugin.keshig.api.entity.KeshigServer;
import io.subutai.plugin.keshig.api.entity.PeerInfo;
import io.subutai.plugin.keshig.impl.KeshigImpl;

import static java.net.HttpURLConnection.HTTP_OK;


public class ServerStatusUpdateHandler implements Runnable
{
    private KeshigImpl keshig;
    private static final Logger LOG = LoggerFactory.getLogger( ServerStatusUpdateHandler.class );


    public ServerStatusUpdateHandler( final KeshigImpl keshig )
    {
        this.keshig = keshig;
    }


    @Override
    public void run()
    {
        LOG.debug( "Starting collecting information about keshig hosts" );

        List<KeshigServer> existingServers = keshig.getAllKeshigServers();

        Map<String, KeshigServer> updatedKeshigServers = getKeshigServerDetails();

        for ( KeshigServer existingKeshigServer : existingServers )
        {
            //if contains info about rh
            if ( updatedKeshigServers.containsKey( existingKeshigServer.getHostname() ) )
            {

                KeshigServer keshigServer1 = updatedKeshigServers.get( existingKeshigServer.getHostname() );

                for ( String ip : existingKeshigServer.getPeers().keySet() )
                {
                    PeerInfo info = keshigServer1.getPeers().get( ip );
                    //copy old values of isFree and UsedBy
                    if ( info != null && keshigServer1.getPeers().get( ip ) != null )
                    {
                        info.setFree( existingKeshigServer.getPeers().get( ip ).isFree() );

                        info.setUsedBy( existingKeshigServer.getPeers().get( ip ).getUsedBy() );
                        if ( info.getIp() != null )
                        {
                            keshigServer1.getPeers().put( info.getIp(), info );
                        }
                    }
                    //update

                }
            }
        }

        keshig.dropAllServers();
        keshig.addKeshigServers( new ArrayList<>( updatedKeshigServers.values() ) );
    }


    private Map<String, KeshigServer> getKeshigServerDetails()
    {

        Map<String, KeshigServer> keshigServers = new HashMap<>();

        Map<String, PeerInfo> peerInfos;

        Set<ResourceHost> resourceHosts = keshig.getPeerManager().getLocalPeer().getResourceHosts();

        for ( ResourceHost resourceHost : resourceHosts )
        {
            LOG.debug( String.format( "Hostname: %s", resourceHost.getHostname() ) );

            peerInfos = getDeployedServersInfo( resourceHost );

            KeshigServer keshigServer = new KeshigServer( resourceHost.getHostname() );

            keshigServer.setLastUpdated( new Date( System.currentTimeMillis() ) );
            keshigServer.setPeers( peerInfos );

            keshigServers.put( keshigServer.getHostname(), keshigServer );
        }


        return keshigServers;
    }


    private Map<String, PeerInfo> getDeployedServersInfo( ResourceHost server )
    {

        Map<String, PeerInfo> peerInfos = new HashMap<>();
        LOG.warn( "Getting deployed peers info from :" + server.getHostname() );

        try
        {
            CommandResult commandResult =
                    server.execute( new RequestBuilder( "/var/qnd/getIPs" ).withRunAs( "ubuntu" ) );

            if ( commandResult.hasSucceeded() )
            {
                String stdOut = commandResult.getStdOut();
                //peer ips
                List<String> ipList = getPeerIps( stdOut );

                for ( String ip : ipList )
                {

                    PeerInfo peerInfo = new PeerInfo();
                    HashMap map = getPeerDetails( ip );
//                    if ( map.size() > 0 )
//                    {
//                        if ( map.get( "status" ).equals( HTTP_OK ) )
//                        {
                            peerInfo.setDetails( map );
                            peerInfo.setIp( ip );
                            peerInfo.setFree( false );
                            peerInfo.setStatus( "OK" );
//                        }
                    //                    }
                    peerInfos.put( ip, peerInfo );

                    LOG.debug( String.format( "Found peer:%s", peerInfo.toString() ) );
                }
            }
            else
            {
                LOG.error( String.format( "Could not obtain running peers on :%s", server.getHostname() ) );
            }
        }
        catch ( CommandException e )
        {
            LOG.error( String.format( "Error obtaining running peers on :%s", server.getHostname() ) );
            LOG.error( e.getMessage() );
        }
        return peerInfos;
    }


    private List<String> getPeerIps( final String stdOut )
    {
        List<String> ips = new ArrayList<>();
        final Pattern pattern = Pattern.compile( "management\\d=.*" );
        final Matcher matcher = pattern.matcher( stdOut );

        while ( matcher.find() )
        {
            final String match = matcher.group();
            final String[] s = match.split( "=" );
            final String ipAddr = s[1].trim();
            ips.add( ipAddr );
        }
        LOG.debug( String.format( "Found IPs on Keshig Server:%s", ips.toString() ) );
        return ips;
    }


    private HashMap getPeerDetails( String ipAddr )
    {
        HashMap serverDetails = new HashMap<>();

        serverDetails.put( "status", 0 );

        try
        {
            String serverUri = String.format( "http://%s:8080/rest/v1/tracker/subutai/about?sptoken=%s", ipAddr,
                    getToken( ipAddr ) );
            LOG.debug( String.format( "Making request to : %s", serverUri ) );
            CloseableHttpClient httpclient = HttpClients.createDefault();

            HttpGet httpPost = new HttpGet( serverUri );
            ResponseHandler<String> responseHandler = new ResponseHandler<String>()
            {
                @Override
                public String handleResponse( final HttpResponse response ) throws IOException
                {
                    int status = response.getStatusLine().getStatusCode();
                    if ( status >= 200 && status < 300 )
                    {
                        LOG.warn( "Response Status POST: " + status );

                        HttpEntity entity = response.getEntity();

                        return entity != null ? EntityUtils.toString( entity ) : null;
                    }
                    else
                    {
                        LOG.warn( "Response Status POST: " + status );
                        throw new ClientProtocolException( "Unexpected response status: " + status );
                    }
                }
            };

            String responseBody = httpclient.execute( httpPost, responseHandler );

            LOG.debug( String.format( "Response from peer:%s ", responseBody ) );
            // print result

            serverDetails = new ObjectMapper().readValue( responseBody, HashMap.class );

            LOG.debug( String.format( "Peer Details:%s ", serverDetails ) );
        }
        catch ( IOException e )
        {
            LOG.error( "Error getting peer info:\n " + e.getMessage() );
            return serverDetails;
        }


        return serverDetails;
    }


    private String getToken( String ipAddr ) throws IOException
    {

        String serverUri =
                String.format( "http://%s:8080/rest/v1/identity/gettoken?username=admin&password=secret", ipAddr );

        LOG.debug( String.format( "Making request to : %s", serverUri ) );

        try ( CloseableHttpClient httpclient = HttpClients.createDefault() )
        {
            HttpGet httpget = new HttpGet( serverUri );

            LOG.debug( "Executing request " + httpget.getRequestLine() );

            // Create a custom response handler
            ResponseHandler<String> responseHandler = new ResponseHandler<String>()
            {
                @Override
                public String handleResponse( final HttpResponse response ) throws IOException
                {
                    int status = response.getStatusLine().getStatusCode();
                    if ( status >= 200 && status < 300 )
                    {
                        LOG.warn( "Response Status GET: " + status );

                        HttpEntity entity = response.getEntity();

                        return entity != null ? EntityUtils.toString( entity ) : null;
                    }
                    else
                    {
                        LOG.warn( "Response Status GET: " + status );
                        throw new ClientProtocolException( "Unexpected response status: " + status );
                    }
                }
            };
            String responseBody = httpclient.execute( httpget, responseHandler );

            LOG.debug( "Response Body: " + responseBody );

            return responseBody;
        }
    }
}
