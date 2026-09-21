param(
    [Parameter(Mandatory=$true)]
    [string]$Username,

    [Parameter(Mandatory=$true)]
    [string]$Password
)

$response = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/realms/finflow/protocol/openid-connect/token" `
    -ContentType "application/x-www-form-urlencoded" `
    -Body @{
        client_id  = "finflow-client"
        username   = $Username
        password   = $Password
        grant_type = "password"
    }

$response.access_token