#!/bin/sh

HOST_IP=$(ip route | grep default | awk '{print $3}')
echo $HOST_IP minecraft >> /etc/hosts

exec /bin/prometheus.real "$@"
