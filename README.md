# Quick Start
[![FOSSA Status](https://app.fossa.io/api/projects/git%2Bgithub.com%2FBaughn%2FPrometheusIntegration.svg?type=shield)](https://app.fossa.io/projects/git%2Bgithub.com%2FBaughn%2FPrometheusIntegration?ref=badge_shield)


* Add PrometheusIntegration to the mods/ directory of your server.
  You can download it from https://madoka.brage.info/release/

* Install docker on your server, by whichever means.

  Debian/Ubuntu: sudo apt-get install docker.io

* Run the following commands.

  This will expose a dashboard on port 3000. Be careful, by default
  this is publicly writable; you'll need a reverse proxy such as nginx
  to lock it down. See the next section for hints as to how.

  The Prometheus instance on port 9090 also needs to be publicly
  accessible. Do not firewall it.

<!-- -->

    MCDASH_PORT=3000
    MCPROM_PORT=9090
    docker run -d --name mcprom -p $MCPROM_PORT:9090 baughn/mcprom
    docker run -d --name mcdash --link mcprom:mcprom -p $MCDASH_PORT:3000 baughn/mcdash

* Finally, visit http://(your hostname):3000/servers and edit the URL
  of the preconfigured Minecraft server to match the public hostname
  of your server.

# Optional follow-up

* Do the same thing client-side. It isn't as useful, but
  Promdash-Integration absolutely works in single-player or for
  multiplayer clients; the same instructions apply.

* Play around with Prometheus. The easiest way is to visit the /graph
  URL of your Prometheus instance; you can do the same with Promdash,
  but it's a little fiddlier.

  You can find more documentation at
  http://prometheus.io/docs/introduction/overview/

* Suggest improvements. Talk to me!

* Set up Nginx as a reverse proxy for Promdash, so other people can't
  edit your settings. While it shouldn't be possible to break out of
  the container, or even into the container, they can absolutely mess
  up your console.

  Here's a good stanza to start with, though note that your Prometheus
  port still needs to be accessible. Be careful, HTTP basic
  authentication sends your password in cleartext, so it is not safe
  to re-use passwords from elsewhere; ideally, use SSL instead.

<!-- -->

    server {
        listen 80;
        server_name promdash.example.com;

        location / {
            proxy_pass http://localhost:3000;
            limit_except GET HEAD {
                auth_basic "Promdash";
                auth_basic_user_file conf.d/htpasswd;
            }
        }
    }


## License
[![FOSSA Status](https://app.fossa.io/api/projects/git%2Bgithub.com%2FBaughn%2FPrometheusIntegration.svg?type=large)](https://app.fossa.io/projects/git%2Bgithub.com%2FBaughn%2FPrometheusIntegration?ref=badge_large)