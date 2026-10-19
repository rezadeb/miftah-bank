# Optional: bake the locustfiles into an image. Not needed for the default setup,
# because k8s/loadgen/ mounts the files from a ConfigMap into the official image.
FROM locustio/locust:2.46.7
COPY locustfile.py locustfile_wave.py /mnt/locust/
