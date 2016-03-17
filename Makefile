deps-prod-free:
	lein with-profile production,datomic-free deps :tree

deps-prod-pro:
	lein with-profile production,datomic-pro deps :tree

build:
	lein test
	docker build .
